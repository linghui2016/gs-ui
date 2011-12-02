/*
 * Copyright 2006 - 2011 
 *     Julien Baudry	<julien.baudry@graphstream-project.org>
 *     Antoine Dutot	<antoine.dutot@graphstream-project.org>
 *     Yoann Pigné		<yoann.pigne@graphstream-project.org>
 *     Guilhelm Savin	<guilhelm.savin@graphstream-project.org>
 * 
 * This file is part of GraphStream <http://graphstream-project.org>.
 * 
 * GraphStream is a library whose purpose is to handle static or dynamic
 * graph, create them from scratch, file or any source and display them.
 * 
 * This program is free software distributed under the terms of two licenses, the
 * CeCILL-C license that fits European law, and the GNU Lesser General Public
 * License. You can  use, modify and/ or redistribute the software under the terms
 * of the CeCILL-C license as circulated by CEA, CNRS and INRIA at the following
 * URL <http://www.cecill.info> or under the terms of the GNU LGPL as published by
 * the Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE.  See the GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 * The fact that you are presently reading this means that you have had
 * knowledge of the CeCILL-C and LGPL licenses and that you accept their terms.
 */
package org.graphstream.ui.j2dviewer
  
import java.awt.{Container, Graphics2D}
import java.util.ArrayList
import java.io.{File, IOException}
import java.awt.image.BufferedImage
import scala.collection.JavaConversions._
import org.graphstream.ui.geom.Point3
import org.graphstream.graph.Element
import org.graphstream.ui.swingViewer.{GraphRenderer, LayerRenderer}
import org.graphstream.ui.graphicGraph.{GraphicGraph, GraphicElement, GraphicNode, GraphicEdge, GraphicSprite, StyleGroup, StyleGroupListener}
import org.graphstream.ui.graphicGraph.stylesheet.Selector
import org.graphstream.ui.util.Selection
import org.graphstream.ui.j2dviewer.renderer._
import javax.imageio.ImageIO
import org.graphstream.ui.swingViewer.GraphRendererBase
import org.graphstream.ui.swingViewer.util.FPSLogger

object GobelinGraphRenderer {
	val DEFAULT_RENDERER = "gobelin_rndr";
}

/**	
 * Gobelin renderer.
 * 
 * The role of this class is to equip each style group with a specific renderer and
 * to call these renderer to redraw the graph when needed. The renderers then equip
 * each node, edge and sprite with a skeleton that gives is main geometry, then
 * selects a skin according to the group style. The skin will be "applied" to
 * each element to draw in the group. The skin will be moved and scaled according
 * to the skeleton.
 * 
 * A render pass begins by using the camera instance to set up the projection (allows
 * to pass from graph units to pixels, make a rotation a zoom or a translation) and
 * render each style group once for the shadows, and once for the real rendering
 * in Z order.
 * 
 * This class also handles a "selection" object that represents the current selection
 * and renders it.
 * 
 * The renderer uses a back end so that it can adapt to multiple rendering
 * targets (here Swing and OpenGL). As the skin are finally responsible
 * for drawing the graph, the back end is also responsible for the skin
 * creation.
 */
class GobelinGraphRenderer extends GraphRendererBase {
	/** Set the view on the view port defined by the metrics. */
	protected val camera = new Camera

	/** The layer renderer for the background (under the graph), can be null. */
	protected var backRenderer:LayerRenderer = null
	
	/** The layer renderer for the foreground (above the graph), can be null. */
	protected var foreRenderer:LayerRenderer = null
	
	/** The rendering back end. */
	protected var backend:Backend = null
	
	/** Optional output for frame-per-second statistics. */
	protected var fpsLogger:FPSLogger = null
	
// Construction
	
  	def open(graph:GraphicGraph, drawingSurface:Container) {
		this.backend = new BackendJ2D		// XXX choose it according to some setting
		
		backend.open(drawingSurface)
	    super.open(graph, backend.drawingSurface)
		graph.setSkeletonFactory(backend.getSkeletonFactory)
  	}
	
  	def close() {
  		if(graph != null) {
  		    super.close
  		    
  		    if(fpsLogger ne null) {
  		        fpsLogger.close
  		        fpsLogger = null
  		    }
  		    
  		    graph.setSkeletonFactory(null)
  		    removeRenderers  		    
  		    backend.close
  			backend = null
  		}
  	}
	
// Access
  	
  	def getCamera():org.graphstream.ui.swingViewer.Camera = camera

  	def findNodeOrSpriteAt(x:Double, y:Double):GraphicElement = camera.findNodeOrSpriteAt(graph, x, y)
 
  	def allNodesOrSpritesIn(x1:Double, y1:Double, x2:Double, y2:Double):ArrayList[GraphicElement] = camera.allNodesOrSpritesIn(graph, x1, y1, x2, y2)
  
   	def renderingSurface:Container = backend.drawingSurface
	
// Access -- Renderer bindings
   	
   	/** Get (and assign if needed) a style renderer to the graphic graph. The renderer will be reused then. */
    protected def getStyleRenderer(graph:GraphicGraph):GraphBackgroundRenderer = {
  		if(graph.getStyle.getRenderer("dr") == null)
  			graph.getStyle.addRenderer("dr", new GraphBackgroundRenderer(graph, graph.getStyle))
  		
  		graph.getStyle.getRenderer("dr").asInstanceOf[GraphBackgroundRenderer]
    }
    
  	/** Get (and assign if needed) a style renderer to a style group. The renderer will be reused then. */
    protected def getStyleRenderer(style:StyleGroup):StyleRenderer = {
  		if( style.getRenderer("dr") == null)
  			style.addRenderer("dr", StyleRenderer(style, this))
    
  		style.getRenderer("dr").asInstanceOf[StyleRenderer]
    }
    
    /** Get (and assign if needed) the style renderer associated with the style group of the element. */
    protected def getStyleRenderer(element:GraphicElement):StyleRenderer = {
  		getStyleRenderer(element.getStyle)
    }
    
    /** Remove all the registered renderers from the graphic graph. */
    protected def removeRenderers() {
        graph.getStyle.removeRenderer("dr")
        graph.getNodeIterator.foreach { node:GraphicNode => node.getStyle.removeRenderer("dr") }
        graph.getEdgeIterator.foreach { edge:GraphicEdge => edge.getStyle.removeRenderer("dr") }
        graph.getSpriteIterator.foreach { sprite:GraphicSprite => sprite.getStyle.removeRenderer("dr") }
    }
    
// Commands -- Rendering
  
  	def render(g:Graphics2D, width:Int, height:Int) {
  	    if(graph != null) {
  	        startFrame
  	        
  		    backend.prepareNewFrame(g)
  		    camera.setBackend(backend)
  		        
  			val sgs = graph.getStyleGroups
  			
  			setupGraphics
  			graph.computeBounds
  			camera.setBounds(graph)
  			camera.setViewport(width, height)
  			getStyleRenderer(graph).render(backend, camera, width, height)
  			renderBackLayer
  
  			camera.pushView(graph)
  			sgs.shadows.foreach {
		  		getStyleRenderer(_).renderShadow(backend, camera)
  			}
  			sgs.zIndex.foreach { groups =>
  				groups.foreach { group =>
		  	  		if(group.getType != Selector.Type.GRAPH) {
		  	  			getStyleRenderer(group).render(backend, camera)
		  	  		}
  				}
  			}
 
  			camera.popView
  			renderForeLayer
  
  			if( selection.renderer == null ) selection.renderer = new SelectionRenderer( selection, graph )
  			selection.renderer.render(backend, camera, width, height )
  			
  	    	endFrame
  	    }
  	}
  	
  	protected def startFrame() {
  	    if((fpsLogger eq null) && graph.hasLabel("ui.log")) {
  	        fpsLogger = new FPSLogger(graph.getLabel("ui.log").toString)
  	    }
  	    
  	    if(! (fpsLogger eq null))
  	    	fpsLogger.beginFrame
  	}
  	
  	protected def endFrame() {
  	    if(! (fpsLogger eq null))
  	        fpsLogger.endFrame
  	}
   	
	protected def renderBackLayer() { if(backRenderer ne null) renderLayer(backRenderer) }
	
	protected def renderForeLayer() { if(foreRenderer ne null) renderLayer(foreRenderer) }
	
	/** Render a back or from layer. */ 
	protected def renderLayer(renderer:LayerRenderer) {
		val metrics = camera.metrics
		
		renderer.render(backend.graphics2D, graph, metrics.ratioPx2Gu,
			metrics.viewport.data(0).toInt,
			metrics.viewport.data(1).toInt,
			metrics.loVisible.x,
			metrics.loVisible.y,
			metrics.hiVisible.x,
			metrics.hiVisible.y)
	}

	/** Setup the graphic pipeline before drawing. */
	protected def setupGraphics() {
       backend.setAntialias(graph.hasAttribute("ui.antialias"))
       backend.setQuality(graph.hasAttribute("ui.quality"))
	}
	
	def screenshot(filename:String, width:Int, height:Int) {
	   	if(filename.toLowerCase.endsWith("png")) {
			val img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
			render(img.createGraphics, width, height)
			val file = new File(filename)
			ImageIO.write(img, "png", file)
	   	} else if(filename.toLowerCase.endsWith("bmp")) {
			// Who, in the world, is still using BMP ???
	   	    val img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
			render(img.createGraphics, width, height)
			val file = new File(filename)
			ImageIO.write(img, "bmp", file)
		} else if(filename.toLowerCase.endsWith("jpg") || filename.toLowerCase.endsWith("jpeg")) {
		    val img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
			render(img.createGraphics, width, height)
			val file = new File(filename)
			ImageIO.write(img, "jpg", file)
		} else {
		    System.err.println("unknown screenshot filename extension %s, saving to jpeg".format(filename))
		    val img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
			render(img.createGraphics, width, height)
			val file = new File(filename+".jpg")
			ImageIO.write(img, "jpg", file)
		}
	}
   
	def setBackLayerRenderer(renderer:LayerRenderer) { backRenderer = renderer }

	def setForeLayoutRenderer(renderer:LayerRenderer) { foreRenderer = renderer }
   
// Commands -- Style group listener
  
    def elementStyleChanged(element:Element, oldStyle:StyleGroup, style:StyleGroup) {
    	// XXX The element renderer should be the listener, not this. ... XXX

    	if(oldStyle ne null) {
    		
    	} else if(oldStyle ne null) {
    		val renderer = oldStyle.getRenderer(J2DGraphRenderer.DEFAULT_RENDERER)

	    	if((renderer ne null ) && renderer.isInstanceOf[JComponentRenderer])
	    		renderer.asInstanceOf[JComponentRenderer].unequipElement(element.asInstanceOf[GraphicElement])
    	}
    }
}