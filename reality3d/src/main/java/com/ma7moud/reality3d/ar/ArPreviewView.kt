package com.ma7moud.reality3d.ar

import android.app.Activity
import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.google.ar.core.*
import com.ma7moud.reality3d.mesh.DepthMesh
import com.ma7moud.reality3d.mesh.MeshMath
import com.ma7moud.reality3d.scan.ArCameraBackgroundRenderer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ArPreviewView(context: Context, private val activity: Activity, private val mesh: DepthMesh) : GLSurfaceView(context), GLSurfaceView.Renderer {
    private var session: Session? = null
    private var background: ArCameraBackgroundRenderer? = null
    private var anchor: Anchor? = null
    private var tapX = -1f; private var tapY = -1f
    private var modelRenderer: ArMeshRenderer? = null
    private var modelYaw = 0f; private var modelScale = if (mesh.isMetric) 1f else 0.28f
    private var lastX = 0f
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean { modelScale = (modelScale * detector.scaleFactor).coerceIn(0.03f, 4f); return true }
    })

    init { setEGLContextClientVersion(3); setRenderer(this); renderMode=RENDERMODE_CONTINUOUSLY; setPreserveEGLContextOnPause(true) }

    fun resumeAr(){queueEvent{runCatching{if(session==null){if(ArCoreApk.getInstance().requestInstall(activity,true)==ArCoreApk.InstallStatus.INSTALL_REQUESTED)return@runCatching;val s=Session(activity);val c=Config(s);if(s.isDepthModeSupported(Config.DepthMode.AUTOMATIC))c.depthMode=Config.DepthMode.AUTOMATIC;c.planeFindingMode=Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL;s.configure(c);session=s};session?.resume()}};super.onResume()}
    fun pauseAr(){queueEvent{runCatching{session?.pause()}};super.onPause()}
    fun clearPlacement(){queueEvent{anchor?.detach();anchor=null}}

    override fun onTouchEvent(e:MotionEvent):Boolean{scaleDetector.onTouchEvent(e);when(e.actionMasked){MotionEvent.ACTION_DOWN->{lastX=e.x;return true};MotionEvent.ACTION_MOVE->{if(anchor!=null&&!scaleDetector.isInProgress){modelYaw+=(e.x-lastX)*0.3f;lastX=e.x};return true};MotionEvent.ACTION_UP->{if(anchor==null){tapX=e.x;tapY=e.y};return true}};return true}
    override fun onSurfaceCreated(gl:GL10?,config:EGLConfig?){GLES30.glEnable(GLES30.GL_DEPTH_TEST);background=ArCameraBackgroundRenderer();modelRenderer=ArMeshRenderer(mesh);session?.setCameraTextureName(background!!.textureId)}
    override fun onSurfaceChanged(gl:GL10?,w:Int,h:Int){GLES30.glViewport(0,0,w,h);val r=if(android.os.Build.VERSION.SDK_INT>=30)activity.display?.rotation?:0 else @Suppress("DEPRECATION") activity.windowManager.defaultDisplay.rotation;session?.setDisplayGeometry(r,w,h)}
    override fun onDrawFrame(gl:GL10?){GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT);val s=session?:return;val bg=background?:return;runCatching{s.setCameraTextureName(bg.textureId)};val frame=runCatching{s.update()}.getOrNull()?:return;bg.draw(frame);val cam=frame.camera;if(cam.trackingState!=TrackingState.TRACKING)return
        if(anchor==null&&tapX>=0f){val hits=frame.hitTest(tapX,tapY);tapX=-1f;tapY=-1f;val hit=hits.firstOrNull{h->when(val t=h.trackable){is Plane->t.isPoseInPolygon(h.hitPose);is Point->true;is DepthPoint->true;else->false}};anchor=hit?.createAnchor()}
        val a=anchor?:return;if(a.trackingState!=TrackingState.TRACKING)return;val view=FloatArray(16);val proj=FloatArray(16);cam.getViewMatrix(view,0);cam.getProjectionMatrix(proj,0,0.05f,50f);modelRenderer?.draw(a.pose,view,proj,modelYaw,modelScale)
    }
}

private class ArMeshRenderer(source:DepthMesh){private val mesh=if(source.normals.size==source.positions.size)source else MeshMath.recalculateNormals(source);private val vertices=fb(mesh.positions);private val normals=fb(mesh.normals);private val colors=fb(mesh.colors?.takeIf{it.size==mesh.vertexCount*4}?:FloatArray(mesh.vertexCount*4){i->if(i%4==3)1f else 0.65f});private val indices=ib(mesh.indices);private val program=createProgram();private val model=FloatArray(16);private val mv=FloatArray(16);private val mvp=FloatArray(16)
    fun draw(pose:Pose,view:FloatArray,proj:FloatArray,yaw:Float,scale:Float){pose.toMatrix(model,0);Matrix.rotateM(model,0,yaw,0f,1f,0f);Matrix.scaleM(model,0,scale,scale,scale);Matrix.multiplyMM(mv,0,view,0,model,0);Matrix.multiplyMM(mvp,0,proj,0,mv,0);GLES30.glUseProgram(program);GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program,"uMvp"),1,false,mvp,0);GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program,"uModel"),1,false,model,0);bind("aPosition",3,vertices);bind("aNormal",3,normals);bind("aColor",4,colors);indices.position(0);GLES30.glDrawElements(GLES30.GL_TRIANGLES,mesh.indices.size,GLES30.GL_UNSIGNED_INT,indices)}
    private fun bind(n:String,s:Int,b:FloatBuffer){val l=GLES30.glGetAttribLocation(program,n);GLES30.glEnableVertexAttribArray(l);b.position(0);GLES30.glVertexAttribPointer(l,s,GLES30.GL_FLOAT,false,0,b)}
    private fun createProgram():Int{fun sh(t:Int,s:String)=GLES30.glCreateShader(t).also{GLES30.glShaderSource(it,s);GLES30.glCompileShader(it)};val v=sh(GLES30.GL_VERTEX_SHADER,"#version 300 es\nuniform mat4 uMvp;uniform mat4 uModel;in vec3 aPosition;in vec3 aNormal;in vec4 aColor;out vec3 n;out vec4 c;void main(){n=normalize(mat3(uModel)*aNormal);c=aColor;gl_Position=uMvp*vec4(aPosition,1.0);}");val f=sh(GLES30.GL_FRAGMENT_SHADER,"#version 300 es\nprecision mediump float;in vec3 n;in vec4 c;out vec4 o;void main(){float d=0.25+0.75*max(dot(normalize(n),normalize(vec3(-0.3,0.8,1.0))),0.0);o=vec4(c.rgb*d,1.0);}");return GLES30.glCreateProgram().also{GLES30.glAttachShader(it,v);GLES30.glAttachShader(it,f);GLES30.glLinkProgram(it)}}
    companion object{private fun fb(a:FloatArray)=ByteBuffer.allocateDirect(a.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply{put(a);position(0)};private fun ib(a:IntArray)=ByteBuffer.allocateDirect(a.size*4).order(ByteOrder.nativeOrder()).asIntBuffer().apply{put(a);position(0)}}}
