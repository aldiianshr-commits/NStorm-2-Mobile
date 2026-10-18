package com.nstorm2.mobile.viewer;

import android.opengl.GLSurfaceView;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLDisplay;

/** Picks an RGB888 + depth24 ES3 config with the requested MSAA level, falling back to no MSAA. */
public final class MsaaConfigChooser implements GLSurfaceView.EGLConfigChooser {
    private static final int EGL_OPENGL_ES3_BIT = 0x40;
    private final int samples;

    public MsaaConfigChooser(int samples) {
        this.samples = samples;
    }

    @Override
    public EGLConfig chooseConfig(EGL10 egl, EGLDisplay display) {
        for (int s : samples > 0 ? new int[]{samples, 0} : new int[]{0}) {
            for (int depth : new int[]{24, 16}) {
                int[] attrs = s > 0 ? new int[]{
                        EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                        EGL10.EGL_DEPTH_SIZE, depth, EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                        EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, s, EGL10.EGL_NONE}
                        : new int[]{
                        EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                        EGL10.EGL_DEPTH_SIZE, depth, EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL10.EGL_NONE};
                int[] count = new int[1];
                EGLConfig[] configs = new EGLConfig[1];
                if (egl.eglChooseConfig(display, attrs, configs, 1, count) && count[0] > 0) return configs[0];
            }
        }
        throw new IllegalStateException("No OpenGL ES 3 config available");
    }
}
