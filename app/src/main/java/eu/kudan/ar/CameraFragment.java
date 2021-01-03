package eu.kudan.ar;

import android.Manifest;
import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static android.hardware.camera2.CameraMetadata.LENS_FACING_BACK;

/**
 * A Fragment responsible for maintaining a camera preview and both image and markerless tracking and image detection.
 */
public class CameraFragment extends Fragment {

    //region Member Variables

    /**
     * A reference to the opened CameraDevice.
     */
    private CameraDevice mCameraDevice;

    /**
     * A callback for managing CameraDevice events.
     */
    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {

            Log.i("CameraDevice", "CameraDevice Opened.");

            mCameraDevice = cameraDevice;

            // Get the KudanCV API key from the Android Manifest.
            String apiKey = getAPIKey();

            // Initialise the native tracking objects.
            initialiseImageTracker(apiKey, mCameraPreviewSize.getWidth(), mCameraPreviewSize.getHeight());
            initialiseArbiTracker(apiKey, mCameraPreviewSize.getWidth(), mCameraPreviewSize.getHeight());

            // Add the image trackable to the native image tracker.
            addTrackable(R.mipmap.lego, "lego");

            // Create the camera preview.
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {

            Log.i("CameraDevice", "CameraDevice Disconnected.");

            // Release the Semaphore to allow the CameraDevice to be closed.
            mCameraOpenCloseLock.release();

            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error) {

            Log.e("CameraDevice", "CameraDevice Error.");

            // Release the Semaphore to allow the CameraDevice to be closed.
            mCameraOpenCloseLock.release();

            cameraDevice.close();
            mCameraDevice = null;

            // Stop the activity.
            Activity activity = getActivity();

            if (null != activity) {
                activity.finish();
            }
        }
    };

    /**
     * A CaptureSession for the camera preview.
     */
    private CameraCaptureSession mCaptureSession;

    /**
     * A reference to the ImageReader that receives new camera preview frames and processes tracking and detection.
     */
    private ImageReader mImageReader;

    /**
     * A CaptureRequest.Builder for the camera preview.
     */
    private CaptureRequest.Builder mPreviewRequestBuilder;

    /**
     * A CaptureRequest generated by mPreviewRequestBuilder.
     */
    private CaptureRequest mPreviewRequest;

    /**
     * Dimensions of the camera preview.
     */
    private Size mCameraPreviewSize = new Size(1920, 1080);

    /**
     * A Semaphore to prevent the camera simultaneously opening and closing.
     */
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);

    /**
     * The CameraSurfaceView on which the camera preview frames and GUI are rendered to.
     */
    private CameraSurfaceView mSurfaceView;

    /**
     * Callback listener for mSurfaceView to manage Surface lifecycle events.
     */
    private SurfaceHolder.Callback mSurfaceCallback = new SurfaceHolder.Callback() {

        @Override
        public void surfaceCreated(SurfaceHolder holder) {

            // Prevent camera device setup if a background thread is not available.
            if (mBackgroundHandler == null) {
                return;
            }

            // Setup the camera only when the Surface has been created to ensure a valid output
            // surface exists when the CameraCaptureSession is created.
            setupCameraDevice();
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
        }
    };

    /**
     * Pre-allocated objects for transforming primitive drawing coordinates from camera frame space
     * to screen space.
     */
    RectF mSrcRect = new RectF();
    RectF mDstRect = new RectF();
    Matrix mCanvasTransform = new Matrix();

    /**
     * A TextView for displaying the current tracking state.
     */
    private TextView mStatusLabel;

    /**
     * A Button that allows the user to change the current tracking type.
     */
    private Button mButton;

    /**
     * Pre-allocated Point objects that retain the projected, screen-space corner coordinates of the tracked object.
     */
    private ArrayList<Point> trackedCorners = new ArrayList<>(4);

    /**
     * Describes the current state of tracking in the most recently processed camera frame.
     */
    TrackerState mTrackerState = TrackerState.IMAGE_DETECTION;

    /**
     * Possible states of tracking available during camera frame processing.
     */
    enum TrackerState {
        IMAGE_DETECTION,
        IMAGE_TRACKING,
        ARBITRACK
    }

    /**
     * Background thread that is responsible for receiving camera frames and rendering GUI elements.
     */
    private HandlerThread mBackgroundThread;

    /**
     * Background handler for running tasks on the background thread.
     */
    private Handler mBackgroundHandler;

    /**
     * Callback listener for ImageReader that handles new camera preview frames received from the CameraDevice.
     */
    private ImageReader.OnImageAvailableListener mImageAvailListener = new ImageReader.OnImageAvailableListener() {

        /**
         * Pre-allocated Bitmap object for holding luma data from the most recent camera frame.
         */
        Bitmap cameraFrame = Bitmap.createBitmap(mCameraPreviewSize.getWidth(), mCameraPreviewSize.getHeight(), Bitmap.Config.ALPHA_8);

        /**
         * Pre-allocated Rect object for holding the dimensions of the camera frame.
         */
        Rect cameraFrameRect = new Rect();

        /**
         * Pre-allocated byte array for holding the raw luma data of the  most recent camera frame.
         */
        byte[] cameraFrameData = new byte[mCameraPreviewSize.getWidth() * mCameraPreviewSize.getHeight()];

        /**
         * Callback method for handling new camera preview frames sent from the CameraDevice.
         *
         * @param reader The ImageReader receiving the new camera frame.
         */
        @Override
        public void onImageAvailable(ImageReader reader) {

            // Synchronize with the tracker state to prevent changes to state mid-processing.
            synchronized (mTrackerState) {

                Image currentCameraImage = reader.acquireLatestImage();

                // Return if no new camera image is available.
                if (currentCameraImage == null) {
                    return;
                }

                int width = currentCameraImage.getWidth();
                int height = currentCameraImage.getHeight();

                // Get the buffer holding the luma data from the YUV-format image.
                ByteBuffer buffer = currentCameraImage.getPlanes()[0].getBuffer();

                // Push the luma data into a byte array.
                buffer.get(cameraFrameData);

                // Update the cameraFrame bitmap with the new image data.
                buffer.rewind();
                cameraFrame.copyPixelsFromBuffer(buffer);

                // Process tracking based on the new camera frame data.
                mTrackerState = processTracking(cameraFrameData, width, height, mTrackerState, trackedCorners);

                // Render the new frame and tracking results to screen.
                renderFrameToScreen(cameraFrame, cameraFrameRect, mTrackerState, trackedCorners);

                // Clean up frame data.
                buffer.clear();
                currentCameraImage.close();
            }
        }
    };

    //endregion

    //region Constructors and Factories

    /**
     * Loads the required JNI native library on creation of CameraFragment.
     */
    static {
        System.loadLibrary("native-lib");
    }

    /**
     * Factory method to create a new CameraFragment.
     *
     * @return a new CameraFragment object.
     */
    public static CameraFragment newInstance() {
        return new CameraFragment();
    }

    public CameraFragment() {

        super();

        // Pre-allocate point objects to store tracked corner data.
        for (int i = 0;i < 4;i++) {
            trackedCorners.add(new Point());
        }
    }

    //endregion

    //region Lifecycle Methods

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.camera_fragment, container, false);
    }

    @Override
    public void onViewCreated(final View view, Bundle savedInstanceState) {

        mSurfaceView = (CameraSurfaceView) view.findViewById(R.id.surface_view);
        mSurfaceView.setAspectRatio(mCameraPreviewSize.getWidth(), mCameraPreviewSize.getHeight());

        mStatusLabel = (TextView) view.findViewById(R.id.status_label);

        mButton = (Button) view.findViewById(R.id.button);
        mButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                buttonPressed(v);
            }
        });
    }

    @Override
    public void onPause() {

        teardownCamera();
        teardownBackgroundThread();

        super.onPause();
    }

    @Override
    public void onResume() {

        super.onResume();

        setupBackgroundThread();

        if (mSurfaceView.getHolder().getSurface().isValid()) {
            setupCameraDevice();
        }
        else {
            mSurfaceView.getHolder().addCallback(mSurfaceCallback);
        }
    }

    //endregion

    //region Setup and Teardown Methods

    /**
     * Gets the KudanCV API key from the Android Manifest.
     *
     * The API key should be contained in a tag of format:
     * <meta-data>
     *     android:name="${PACKAGE_NAME}.API_KEY
     *     android:value="${YOUR_API_KEY}
     * </meta-data>
     *
     * @return the API key
     */
    private String getAPIKey() {

        String appPackageName = getActivity().getPackageName();

        try {
            ApplicationInfo app = getActivity()
                    .getPackageManager()
                    .getApplicationInfo(appPackageName, PackageManager.GET_META_DATA);

            Bundle bundle = app.metaData;

            String apiKeyID = appPackageName + ".API_KEY";

            if (bundle == null) {
                throw new RuntimeException("No manifest meta-data tags exist.\n\nMake sure the AndroidManifest.xml file contains a <meta-data\n\tandroid:name=\"" + apiKeyID + "\"\n\tandroid:value=\"${YOUR_API_KEY}\"></meta-data>\n");
            }

            String apiKey = bundle.getString(apiKeyID);

            if (apiKey == null) {
                throw new RuntimeException("Could not get API Key from Android Manifest meta-data.\n\nMake sure the AndroidManifest.xml file contains a <meta-data\n\tandroid:name=\"" + apiKeyID + "\"\n\tandroid:value=\"${YOUR_API_KEY}\"></meta-data>\n");
            }

            return apiKey;

        }
        catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException("Cannot find Package with name \"" + appPackageName + "\". Cannot load API key.");
        }
    }

    /**
     * Sets up a new background thread and it's Handler.
     */
    private void setupBackgroundThread() {

        mBackgroundThread = new HandlerThread("BackgroundCameraThread");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    /**
     * Sets up a new CameraDevice if camera permissions have been granted by the user.
     */
    private void setupCameraDevice() {

        // Check for camera permissions.
        if (ActivityCompat.checkSelfPermission(getActivity(), Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            throw new RuntimeException("Camera permissions must be granted to function.");
        }

        CameraManager manager = (CameraManager) getActivity().getSystemService(Context.CAMERA_SERVICE);

        try {
            String[] cameras = manager.getCameraIdList();

            // Find back-facing camera.
            for (String camera : cameras) {

                CameraCharacteristics cameraCharacteristics = manager.getCameraCharacteristics(camera);

                // Reject all cameras but the back-facing camera.
                if (cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) != LENS_FACING_BACK) {
                    continue;
                }

                try {
                    if (!mCameraOpenCloseLock.tryAcquire(3000, TimeUnit.MILLISECONDS)) {
                        throw new RuntimeException(("Camera lock cannot be acquired during opening."));
                    }

                    // Open camera. Events are sent to the mStateCallback listener and handled on the background thread.
                    manager.openCamera(camera, mStateCallback, mBackgroundHandler);

                    // Open one camera only.
                    return;

                }
                catch (InterruptedException e) {
                    throw new RuntimeException("Camera open/close semaphore cannot be acquired");
                }
            }

        } catch (CameraAccessException e) {
            throw new RuntimeException("Cannot access camera.");
        }
    }

    /**
     * Creates a new CameraCaptureSession for the camera preview.
     */
    private void createCameraPreviewSession() {

        try {

            // Create an ImageReader instance that buffers two camera images so there is always room for most recent camera frame.
            mImageReader = ImageReader.newInstance(mCameraPreviewSize.getWidth(), mCameraPreviewSize.getHeight(), ImageFormat.YUV_420_888, 2);

            // Handle all new camera frames received on the background thread.
            mImageReader.setOnImageAvailableListener(mImageAvailListener, mBackgroundHandler);

            // Set up a CaptureRequest.Builder with the output Surface of the ImageReader.
            mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mPreviewRequestBuilder.addTarget(mImageReader.getSurface());

            // Create the camera preview CameraCaptureSession.
            mCameraDevice.createCaptureSession(Arrays.asList(mImageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                            // The camera is already closed
                            if (mCameraDevice == null) {
                                return;
                            }

                            // When the session is ready, we start displaying the preview.
                            mCaptureSession = cameraCaptureSession;
                            try {
                                // Auto focus should be continuous for camera preview.
                                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

                                // Finally, start displaying the camera preview.
                                mPreviewRequest = mPreviewRequestBuilder.build();
                                mCaptureSession.setRepeatingRequest(mPreviewRequest,
                                        null, mBackgroundHandler);

                                // Release the Semaphore to allow the CameraDevice to be closed.
                                mCameraOpenCloseLock.release();

                            }
                            catch (CameraAccessException e) {
                                throw new RuntimeException("Cannot access camera during CameraCaptureSession setup.");
                            }
                        }

                        @Override
                        public void onConfigureFailed(
                                @NonNull CameraCaptureSession cameraCaptureSession) {
                            throw new RuntimeException("Camera capture session configuration failed.");
                        }
                    }, null
            );
        } catch (CameraAccessException e) {
            throw new RuntimeException("Cannot access camera during CameraCaptureSession setup.");
        }
    }

    /**
     * Tears down and closes the camera device and session.
     */
    private void teardownCamera() {

        try {
            // Prevent the teardown from occuring at the same time as setup.
            mCameraOpenCloseLock.acquire();

            if (mCaptureSession != null) {
                mCaptureSession.close();
                mCaptureSession = null;
            }
            if (mCameraDevice != null) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
            if (mImageReader != null) {
                mImageReader.close();
                mImageReader = null;
            }

        }
        catch (InterruptedException e) {
            e.printStackTrace();
        }
        finally {
            mCameraOpenCloseLock.release();
        }
    }

    /**
     * Stops the background thread and handler.
     */
    private void teardownBackgroundThread() {

        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        }
        catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    //endregion

    //region Frame Processing Methods

    /**
     * Processes tracking on a camera frame's data.
     *
     * @param data Array containing the camera frame luma data to be processed.
     * @param width Width of the camera frame.
     * @param height Height of the camera frame.
     * @param currentState The current tracking state of the system.
     * @param projectedTrackingCorners ArrayList containing a set of four Points into which projected, screen-space coordinates of the tracked primitive is copied if tracking completes successfully.
     * @return The new tracking state of the system.
     */
    private TrackerState processTracking(byte[] data, int width, int height, TrackerState currentState, ArrayList<Point> projectedTrackingCorners) {

        float[] trackedData = null;
        TrackerState newState = currentState;

        // Perform image detection and tracking.
        if (currentState != TrackerState.ARBITRACK) {

            // Native call to the image tracking and detection object.
            trackedData = processImageTrackerFrame(
                    data,
                    width,
                    height,
                    1, /*One channel as we are processing luma data only*/
                    0,
                    false
            );

            if (trackedData != null) {
                newState = TrackerState.IMAGE_TRACKING;
            } else {
                newState = TrackerState.IMAGE_DETECTION;
            }
        }

        // Else perform markerless tracking.
        else if (currentState == TrackerState.ARBITRACK) {

            // Native call to the markerless tracking object.
            trackedData = processArbiTrackerFrame(data, width, height, 1, 0, false);
        }

        if (trackedData != null) {

            // Set the supplied point ArrayList values to the returned projected tracking coordinates.
            projectedTrackingCorners.get(0).set(Math.round(trackedData[2]), Math.round(trackedData[3]));
            projectedTrackingCorners.get(1).set(Math.round(trackedData[4]), Math.round(trackedData[5]));
            projectedTrackingCorners.get(2).set(Math.round(trackedData[6]), Math.round(trackedData[7]));
            projectedTrackingCorners.get(3).set(Math.round(trackedData[8]), Math.round(trackedData[9]));
        }

        return newState;
    }

    /**
     * Renders a camera frame and tracking data to screen.
     *
     * @param cameraFrame Bitmap of Bitmap.Config.ALPHA_8 containing camera frame luma data.
     * @param cameraFrameRect Dimensions of the camera frame of format (0, 0, width, height).
     * @param currentState The current tracking state of the system.
     * @param primitiveCorners ArrayList containing the set of four Points in which projected, screen-space coordinates of the tracked primitive resides.
     */
    private void renderFrameToScreen(Bitmap cameraFrame, Rect cameraFrameRect, TrackerState currentState, ArrayList<Point> primitiveCorners) {

        // Define UI element values.
        final int buttonColor;
        final String buttonText;
        final String primitiveLabel;
        final String statusLabel;
        final Drawing.DrawingPrimitive primitive;

        if (currentState == TrackerState.IMAGE_DETECTION) {

            buttonColor = Color.rgb(255, 162, 0);
            buttonText = "Start Arbitrack";
            primitiveLabel = "";
            statusLabel = "Looking for image...";

            primitive = Drawing.DrawingPrimitive.DRAWING_NOTHING;

        }
        else if (currentState == TrackerState.IMAGE_TRACKING) {

            buttonColor = Color.BLUE;
            buttonText = "Start Arbitrack from marker";
            primitiveLabel = "Lego";
            statusLabel = "Tracking image";

            primitive = Drawing.DrawingPrimitive.DRAWING_RECTANGLE;

        } else if (currentState == TrackerState.ARBITRACK) {

            buttonColor = Color.GREEN;
            buttonText = "Stop Arbitrack";
            primitiveLabel = "Arbitrack";
            statusLabel = "Running arbitrack";

            primitive = Drawing.DrawingPrimitive.DRAWING_GRID;
        } else {

            buttonColor = Color.TRANSPARENT;
            buttonText = "";
            primitiveLabel = "";
            statusLabel = "";
            primitive = Drawing.DrawingPrimitive.DRAWING_NOTHING;
        }

        // Update Android GUI.
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {

                mButton.setBackgroundColor(buttonColor);
                mButton.setText(buttonText);

                mStatusLabel.setText(statusLabel);
                mStatusLabel.setTextColor(buttonColor);
            }
        });

        // Draw everything to screen. Drawing is achieved with Android's Canvas classes, if high
        // performance is required, consider using OpenGL to draw instead.

        // Calculate scaling that needs to be applied to the canvas to fit drawing to screen.
        mSrcRect.set(0, 0, mCameraPreviewSize.getWidth(), mCameraPreviewSize.getHeight());
        mDstRect.set(0, 0, mSurfaceView.getWidth(), mSurfaceView.getHeight());
        mCanvasTransform.setRectToRect(mSrcRect, mDstRect, Matrix.ScaleToFit.END);

        // Lock the CameraSurfaceView Surface for drawing.
        Canvas canvas = mSurfaceView.getHolder().getSurface().lockCanvas(mSurfaceView.getClipBounds());

        // Draw the background camera image.
        Drawing.drawBackground(
                canvas,
                cameraFrame
        );

        // Draw the tracking primitive.
        Drawing.drawPrimitive(
                canvas,
                mCanvasTransform,
                primitive,
                primitiveCorners.get(0),
                primitiveCorners.get(1),
                primitiveCorners.get(2),
                primitiveCorners.get(3),
                primitiveLabel
        );

        // Unlock the CameraSurfaceView Surface to render to screen.
        mSurfaceView.getHolder().getSurface().unlockCanvasAndPost(canvas);
    }

    //endregion

    //region Utility Methods

    /**
     * Adds an image trackable image to the native image tracker object.
     *
     * @param resourceID A reference to the image asset that should be used as a trackable.
     * @param name The name of the trackable that should be used internally for ID.
     */
    public void addTrackable(int resourceID, String name) {

        // Create a bitmap from the resource file.
        Bitmap image = BitmapFactory.decodeResource(getResources(), resourceID);

        // Pass the bitmap to JNI for addition to the image tracker.
        boolean success = addTrackableToImageTracker(image, name);

        if (!success) {
            throw new RuntimeException("Trackable could not be added to image tracker.");
        }
    }

    //endregion

    //region UI Callback Methods

    /**
     * Listener method for changing the tracking state of the system based on user input.
     *
     * @param view The view that triggered the listener callback method.
     */
    public void buttonPressed(View view) {

        // Synchronize with the tracker state to prevent changes to state mid-processing.
        synchronized (mTrackerState) {

            if (mTrackerState == TrackerState.IMAGE_DETECTION) {

                startArbiTracker(false);

                mTrackerState = TrackerState.ARBITRACK;

            } else if (mTrackerState == TrackerState.IMAGE_TRACKING) {

                startArbiTracker(true);

                mTrackerState = TrackerState.ARBITRACK;

            } else if (mTrackerState == TrackerState.ARBITRACK) {

                stopArbiTracker();

                mTrackerState = TrackerState.IMAGE_DETECTION;
            }
        }
    }

    //endregion

    //region Native Methods

    /**
     * Initialise the native image tracker object.
     *
     * @param key The KudanCV API key.
     * @param width The width of camera frames that will be processed.
     * @param height The height of camera frames that will be processed.
     */
    private native void initialiseImageTracker(String key, int width, int height);

    /**
     * Initialise the native markerless tracker object.
     *
     * @param key The KudanCV API key.
     * @param width The width of camera frames that will be processed.
     * @param height The height of camera frames that will be processed.
     */
    private native void initialiseArbiTracker(String key, int width, int height);

    /**
     * Starts the native markerless tracker ready for tracking.
     *
     * @param startFromImageTrackable Should the initial markerless primitive be started at the position of the currently tracked image trackable.
     */
    private native void startArbiTracker(boolean startFromImageTrackable);

    /**
     * Stops the native markerless tracker.
     */
    private native void stopArbiTracker();

    /**
     * Adds an image as a trackable to the native image tracker object.
     *
     * @param image Bitmap containing a Bitmap.Config.ARGB_8888 image to be used as a trackable.
     * @param name The name of the trackable to be used for internal ID.
     * @return Whether the trackable was added to the image tracker object successfully.
     */
    private native boolean addTrackableToImageTracker(
            Bitmap image,
            String name);

    /**
     * Processes an image through the native image tracker object and returns tracking data.
     *
     * @param image Array containing the camera frame data.
     * @param width The width of the camera image.
     * @param height The height of the camera image.
     * @param channels The number of channels contained in the camera frame.
     * @param padding Padding in the camera frame data.
     * @param requiresFlip Whether the camera frame should be flipped before tracking.
     * @return Array containing the screen-space projected corner coordinates of the tracking primitive.
     */
    private native float[] processImageTrackerFrame(
            byte[] image,
            int width,
            int height,
            int channels,
            int padding,
            boolean requiresFlip);

    /**
     * Processes an image through the native markerless tracker object and returns tracking data.
     *
     * @param image Array containing the camera frame data.
     * @param width The width of the camera image.
     * @param height The height of the camera image.
     * @param channels The number of channels contained in the camera frame.
     * @param padding Padding in the camera frame data.
     * @param requiresFlip Whether the camera frame should be flipped before tracking.
     * @return Array containing the screen-space projected corner coordinates of the tracking primitive.
     */
    private native float[] processArbiTrackerFrame(
            byte[] image,
            int width,
            int height,
            int channels,
            int padding,
            boolean requiresFlip);

    //endregion
}
