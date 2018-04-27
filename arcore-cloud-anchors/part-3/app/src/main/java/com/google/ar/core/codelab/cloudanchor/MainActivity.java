/*
 * Copyright 2018 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ar.core.codelab.cloudanchor;

import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.support.annotation.GuardedBy;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.Toast;
import com.google.ar.core.Anchor;
import com.google.ar.core.Anchor.CloudAnchorState;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Config.CloudAnchorMode;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.Point;
import com.google.ar.core.Point.OrientationMode;
import com.google.ar.core.PointCloud;
import com.google.ar.core.Session;
import com.google.ar.core.Trackable;
import com.google.ar.core.TrackingState;
import com.google.ar.core.examples.java.common.helpers.CameraPermissionHelper;
import com.google.ar.core.examples.java.common.helpers.DisplayRotationHelper;
import com.google.ar.core.examples.java.common.helpers.FullScreenHelper;
import com.google.ar.core.examples.java.common.helpers.SnackbarHelper;
import com.google.ar.core.examples.java.common.rendering.BackgroundRenderer;
import com.google.ar.core.examples.java.common.rendering.ObjectRenderer;
import com.google.ar.core.examples.java.common.rendering.ObjectRenderer.BlendMode;
import com.google.ar.core.examples.java.common.rendering.PlaneRenderer;
import com.google.ar.core.examples.java.common.rendering.PointCloudRenderer;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import java.io.IOException;
import java.util.Collection;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * This is a simple example that shows how to create an augmented reality (AR) application using the
 * ARCore API. The application will display any detected planes and will allow the user to tap on a
 * plane to place a 3d model of the Android robot.
 */
public class MainActivity extends AppCompatActivity implements GLSurfaceView.Renderer {
  private static final String TAG = MainActivity.class.getSimpleName();

  // Rendering. The Renderers are created here, and initialized when the GL surface is created.
  private GLSurfaceView surfaceView;
  private final BackgroundRenderer backgroundRenderer = new BackgroundRenderer();
  private final ObjectRenderer virtualObject = new ObjectRenderer();
  private final ObjectRenderer virtualObjectShadow = new ObjectRenderer();
  private final PlaneRenderer planeRenderer = new PlaneRenderer();
  private final PointCloudRenderer pointCloudRenderer = new PointCloudRenderer();

  // Matrices pre-allocated here to reduce the number of allocations on every frame draw.
  private final float[] anchorMatrix = new float[16];
  private final float[] projectionMatrix = new float[16];
  private final float[] viewMatrix = new float[16];
  private final float[] colorCorrectionRgba = new float[4];

  // Locks needed for synchronization.
  private final Object singleTapLock = new Object();
  private final Object anchorLock = new Object();

  // Tap handling and UI. This app allows you to place at most one anchor.
  @GuardedBy("singleTapLock")
  private MotionEvent queuedSingleTap;

  private final SnackbarHelper snackbarHelper = new SnackbarHelper();
  private GestureDetector gestureDetector;
  private DisplayRotationHelper displayRotationHelper;

  // ARCore components
  private Session session;
  private boolean installRequested;

  @Nullable
  @GuardedBy("anchorLock")
  private Anchor anchor;

  private enum CloudOperation {
    NONE,
    HOSTING,
    RESOLVING
  }

  private CloudOperation currentOperation;
  private final StorageManager storageManager = new StorageManager();

  private void handleTapOnDraw(TrackingState currentTrackingState, Frame currentFrame) {
    synchronized (singleTapLock) {
      synchronized (anchorLock) {
        if (anchor == null
            && queuedSingleTap != null
            && currentTrackingState == TrackingState.TRACKING
            && currentOperation == CloudOperation.NONE) {
          for (HitResult hit : currentFrame.hitTest(queuedSingleTap)) {
            if (shouldCreateAnchorWithHit(hit)) {
              Anchor anchor = session.hostCloudAnchor(hit.createAnchor());
              snackbarHelper.showMessage(this, "Now hosting anchor...");
              currentOperation = CloudOperation.HOSTING;
              setNewAnchor(anchor);
              break;
            }
          }
        }
      }
      queuedSingleTap = null;
    }
  }

  /**
   * Returns {@code true} if and only if {@code hit} can be used to create an anchor.
   *
   * <p>Checks if a plane was hit and if the hit was inside the plane polygon, or if an oriented
   * point was hit. We only want to create an anchor if the hit satisfies these conditions.
   */
  private static boolean shouldCreateAnchorWithHit(HitResult hit) {
    Trackable trackable = hit.getTrackable();
    if (trackable instanceof Plane) {
      // Check if any plane was hit, and if it was hit inside the plane polygon
      return ((Plane) trackable).isPoseInPolygon(hit.getHitPose());
    } else if (trackable instanceof Point) {
      // Check if an oriented point was hit.
      return ((Point) trackable).getOrientationMode() == OrientationMode.ESTIMATED_SURFACE_NORMAL;
    }
    return false;
  }

  private void checkUpdatedAnchors(Collection<Anchor> updatedAnchors) {
    synchronized (anchorLock) {
      if (anchor == null) {
        return;
      }
      if (updatedAnchors.contains(anchor)) {
        CloudAnchorState cloudState = anchor.getCloudAnchorState();
        if (currentOperation == CloudOperation.HOSTING) {
          if (cloudState.isError()) {
            snackbarHelper.showMessageWithDismiss(this, "Error hosting anchor: " + cloudState);
            currentOperation = CloudOperation.NONE;
          } else if (cloudState == CloudAnchorState.SUCCESS) {
            int shortCode = storageManager.nextShortCode(this);
            storageManager.storeUsingShortCode(this, shortCode, anchor.getCloudAnchorId());
            snackbarHelper.showMessageWithDismiss(
                this, "Anchor hosted successfully! Cloud Short Code: " + shortCode);
            currentOperation = CloudOperation.NONE;
          }
        } else if (currentOperation == CloudOperation.RESOLVING) {
          if (cloudState.isError()) {
            snackbarHelper.showMessageWithDismiss(this, "Error resolving anchor: " + cloudState);
            currentOperation = CloudOperation.NONE;
          } else if (cloudState == CloudAnchorState.SUCCESS) {
            snackbarHelper.showMessageWithDismiss(this, "Anchor resolved successfully!");
            currentOperation = CloudOperation.NONE;
          }
        }
      }
    }
  }

  private void onResolveOkPressed(String dialogValue) {
    synchronized (anchorLock) {
      if (anchor != null) {
        snackbarHelper.showMessageWithDismiss(this, "Please clear anchor first.");
        return;
      }
    }
    int shortCode = Integer.parseInt(dialogValue);
    String cloudAnchorId = storageManager.getCloudAnchorID(this, shortCode);
    Anchor resolvedAnchor = session.resolveCloudAnchor(cloudAnchorId);
    setNewAnchor(resolvedAnchor);
    snackbarHelper.showMessage(this, "Now resolving anchor...");
    currentOperation = CloudOperation.RESOLVING;
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    surfaceView = findViewById(R.id.surfaceview);
    displayRotationHelper = new DisplayRotationHelper(this);

    // Set up tap listener.
    gestureDetector =
        new GestureDetector(
            this,
            new GestureDetector.SimpleOnGestureListener() {
              @Override
              public boolean onSingleTapUp(MotionEvent e) {
                synchronized (singleTapLock) {
                  queuedSingleTap = e;
                }
                return true;
              }

              @Override
              public boolean onDown(MotionEvent e) {
                return true;
              }
            });
    surfaceView.setOnTouchListener((v, event) -> gestureDetector.onTouchEvent(event));

    // Set up renderer.
    surfaceView.setPreserveEGLContextOnPause(true);
    surfaceView.setEGLContextClientVersion(2);
    surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0); // Alpha used for plane blending.
    surfaceView.setRenderer(this);
    surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
    installRequested = false;

    // Initialize the "Clear" button. Clicking it will clear the current anchor, if it exists.
    Button clearButton = findViewById(R.id.clear_button);
    clearButton.setOnClickListener((view) -> setNewAnchor(null));

    Button resolveButton = findViewById(R.id.resolve_button);
    resolveButton.setOnClickListener(
        (view) -> {
          ResolveDialogFragment dialog = new ResolveDialogFragment();
          dialog.setOkListener(this::onResolveOkPressed);
          dialog.show(getSupportFragmentManager(), "Resolve");
        });

    currentOperation = CloudOperation.NONE;
  }

  @Override
  protected void onResume() {
    super.onResume();

    if (session == null) {
      Exception exception = null;
      int messageId = -1;
      try {
        switch (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
          case INSTALL_REQUESTED:
            installRequested = true;
            return;
          case INSTALLED:
            break;
        }

        // ARCore requires camera permissions to operate. If we did not yet obtain runtime
        // permission on Android M and above, now is a good time to ask the user for it.
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
          CameraPermissionHelper.requestCameraPermission(this);
          return;
        }
        session = new Session(this);
      } catch (UnavailableArcoreNotInstalledException e) {
        messageId = R.string.snackbar_arcore_unavailable;
        exception = e;
      } catch (UnavailableApkTooOldException e) {
        messageId = R.string.snackbar_arcore_too_old;
        exception = e;
      } catch (UnavailableSdkTooOldException e) {
        messageId = R.string.snackbar_arcore_sdk_too_old;
        exception = e;
      } catch (Exception e) {
        messageId = R.string.snackbar_arcore_exception;
        exception = e;
      }

      if (exception != null) {
        snackbarHelper.showError(this, getString(messageId));
        Log.e(TAG, "Exception creating session", exception);
        return;
      }

      // Create default config and check if supported.
      Config config = new Config(session);
      config.setCloudAnchorMode(CloudAnchorMode.ENABLED);
      session.configure(config);
    }

    // Note that order matters - see the note in onPause(), the reverse applies here.
    try {
      session.resume();
    } catch (CameraNotAvailableException e) {
      // In some cases (such as another camera app launching) the camera may be given to
      // a different app instead. Handle this properly by showing a message and recreate the
      // session at the next iteration.
      snackbarHelper.showError(this, getString(R.string.snackbar_camera_unavailable));
      session = null;
      return;
    }
    surfaceView.onResume();
    displayRotationHelper.onResume();
  }

  @Override
  public void onPause() {
    super.onPause();
    if (session != null) {
      // Note that the order matters - GLSurfaceView is paused first so that it does not try
      // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
      // still call session.update() and get a SessionPausedException.
      displayRotationHelper.onPause();
      surfaceView.onPause();
      session.pause();
    }
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
    if (!CameraPermissionHelper.hasCameraPermission(this)) {
      Toast.makeText(this, "Camera permission is needed to run this application", Toast.LENGTH_LONG)
          .show();
      if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
        // Permission denied with checking "Do not ask again".
        CameraPermissionHelper.launchPermissionSettings(this);
      }
      finish();
    }
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus);
  }

  @Override
  public void onSurfaceCreated(GL10 gl, EGLConfig config) {
    GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);

    // Prepare the rendering objects. This involves reading shaders, so may throw an IOException.
    try {
      // Create the texture and pass it to ARCore session to be filled during update().
      backgroundRenderer.createOnGlThread(this);
      planeRenderer.createOnGlThread(this, "models/trigrid.png");
      pointCloudRenderer.createOnGlThread(this);

      virtualObject.createOnGlThread(this, "models/andy.obj", "models/andy.png");
      virtualObject.setMaterialProperties(0.0f, 2.0f, 0.5f, 6.0f);

      virtualObjectShadow.createOnGlThread(
          this, "models/andy_shadow.obj", "models/andy_shadow.png");
      virtualObjectShadow.setBlendMode(BlendMode.Shadow);
      virtualObjectShadow.setMaterialProperties(1.0f, 0.0f, 0.0f, 1.0f);

    } catch (IOException ex) {
      Log.e(TAG, "Failed to read an asset file", ex);
    }
  }

  @Override
  public void onSurfaceChanged(GL10 gl, int width, int height) {
    displayRotationHelper.onSurfaceChanged(width, height);
    GLES20.glViewport(0, 0, width, height);
  }

  @Override
  public void onDrawFrame(GL10 gl) {
    // Clear screen to notify driver it should not load any pixels from previous frame.
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

    if (session == null) {
      return;
    }
    // Notify ARCore session that the view size changed so that the perspective matrix and
    // the video background can be properly adjusted.
    displayRotationHelper.updateSessionIfNeeded(session);

    try {
      session.setCameraTextureName(backgroundRenderer.getTextureId());

      // Obtain the current frame from ARSession. When the configuration is set to
      // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
      // camera framerate.
      Frame frame = session.update();
      Camera camera = frame.getCamera();
      TrackingState cameraTrackingState = camera.getTrackingState();

      // Check updated anchors.
      checkUpdatedAnchors(frame.getUpdatedAnchors());

      // Handle taps.
      handleTapOnDraw(cameraTrackingState, frame);

      // Draw background.
      backgroundRenderer.draw(frame);

      // If not tracking, don't draw 3d objects.
      if (cameraTrackingState == TrackingState.PAUSED) {
        return;
      }

      // Get projection and camera matrices.
      camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f);
      camera.getViewMatrix(viewMatrix, 0);

      // Visualize tracked points.
      PointCloud pointCloud = frame.acquirePointCloud();
      pointCloudRenderer.update(pointCloud);
      pointCloudRenderer.draw(viewMatrix, projectionMatrix);

      // Application is responsible for releasing the point cloud resources after
      // using it.
      pointCloud.release();

      // Visualize planes.
      planeRenderer.drawPlanes(
          session.getAllTrackables(Plane.class), camera.getDisplayOrientedPose(), projectionMatrix);

      // Visualize anchor.
      boolean shouldDrawAnchor = false;
      synchronized (anchorLock) {
        if (anchor != null && anchor.getTrackingState() == TrackingState.TRACKING) {
          frame.getLightEstimate().getColorCorrection(colorCorrectionRgba, 0);

          // Get the current pose of an Anchor in world space. The Anchor pose is updated
          // during calls to session.update() as ARCore refines its estimate of the world.
          anchor.getPose().toMatrix(anchorMatrix, 0);
          shouldDrawAnchor = true;
        }
      }
      if (shouldDrawAnchor) {
        float scaleFactor = 1.0f;
        frame.getLightEstimate().getColorCorrection(colorCorrectionRgba, 0);

        // Update and draw the model and its shadow.
        virtualObject.updateModelMatrix(anchorMatrix, scaleFactor);
        virtualObjectShadow.updateModelMatrix(anchorMatrix, scaleFactor);
        virtualObject.draw(viewMatrix, projectionMatrix, colorCorrectionRgba);
        virtualObjectShadow.draw(viewMatrix, projectionMatrix, colorCorrectionRgba);
      }
    } catch (Throwable t) {
      // Avoid crashing the application due to unhandled exceptions.
      Log.e(TAG, "Exception on the OpenGL thread", t);
    }
  }

  private void setNewAnchor(@Nullable Anchor newAnchor) {
    synchronized (anchorLock) {
      if (anchor != null) {
        anchor.detach();
      }
      anchor = newAnchor;
    }
  }
}