package com.example.minimal_precision_data_collection;


import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Range;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Chronometer;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;


public class CaptureVideoFragment extends Fragment
{
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    private static final String TAG = "HSVFragment";
    private static final int REQUEST_VIDEO_PERMISSIONS = 1;
    private static final String FRAGMENT_DIALOG = "dialog";

    private static final String[] VIDEO_PERMISSIONS = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
    };

    static
    {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }
    public static final Integer GAZE_POINT_WIDTH = 20;
    public static final Float GAZE_POINT_DISTANCE_CM = 0.25f;

    /**
     * An {@link AutoFitTextureView} for camera preview.
     */
    private AutoFitTextureView mTextureView;

    /**
     * Button to record video
     */
//    private AppCompatCheckBox mRecButtonVideo;
    private View mRecButtonVideo;
    private Chronometer mChronometer;
    private TextView mInfo;

    /**
     * A refernce to the opened {@link CameraDevice}.
     */
    private CameraDevice mCameraDevice;

    /**
     * A reference to the current {@link CameraCaptureSession} for
     * preview.
     */
    private CameraCaptureSession mPreviewSession;

    /**
     * {@link TextureView.SurfaceTextureListener} handles several lifecycle events on a
     * {@link TextureView}.
     */
    private TextureView.SurfaceTextureListener mSurfaceTextureListener
            = new TextureView.SurfaceTextureListener()
    {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height)
        {
            openCamera(width, height);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height)
        {
            startPreview();
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture)
        {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture)
        {
        }

    };

    /**
     * The {@link Size} of camera preview.
     */
    private Size mPreviewSize;

    private Size mVideoSize;

    public String mNextVideoFilePath;
    /**
     * Camera preview.
     */
    private CaptureRequest.Builder mPreviewBuilder;
    /**
     * MediaRecorder
     */
    private MediaRecorder mMediaRecorder;

    private List<Surface> surfaces = new ArrayList<>();

    /**
     * Whether the app is recording video now
     */
    public boolean mIsRecordingVideo = false;

    /**
     * An additional thread for running tasks that shouldn't block the UI.
     */
    private HandlerThread mBackgroundThread;

    /**
     * A {@link Handler} for running tasks in the background.
     */
    private Handler mBackgroundHandler;

    /**
     * A {@link Semaphore} to prevent the app from exiting before closing the camera.
     */
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);

    /**
     * mCameraCharacteristics用于确认打开的摄像头的特性。
     */
    CameraCharacteristics mCameraCharacteristics;

    /**
     * {@link CameraDevice.StateCallback} is called when {@link CameraDevice} changes its status.
     */
    private CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback()
    {
        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice)
        {
            mCameraDevice = cameraDevice;
            startPreview();
            mCameraOpenCloseLock.release();
            if (null != mTextureView)
            {
                configureTransform(mTextureView.getWidth(), mTextureView.getHeight());
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice)
        {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error)
        {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
            Activity activity = getActivity();
            if (null != activity)
            {
                activity.finish();
            }
        }
    };

    public static CaptureVideoFragment newInstance()
    {
        return new CaptureVideoFragment();
    }

    public static class ViewAndName
    {
        public View mView;
        public String mName;

        public ViewAndName(View view, String name)
        {
            mView = view;
            mName = name;
        }
    }
    public ArrayList<ViewAndName> mViewsForGaze = new ArrayList<>();
    public ArrayList<Integer> mIndicesForGaze = new ArrayList<>();
    public Integer mGazePointIndex = 0;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState)
    {
        View rootView = inflater.inflate(R.layout.camera, container, false);

        // 获取屏幕的分辨率及具体尺寸。
        DisplayMetrics displayMetrics = new DisplayMetrics();
        requireActivity().getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);

        int screenWidthPixels = displayMetrics.widthPixels;
        int screenHeightPixels = displayMetrics.heightPixels;

        // 获取屏幕的物理尺寸（以厘米为单位）
        DisplayMetrics realMetrics = new DisplayMetrics();
        requireActivity().getWindowManager().getDefaultDisplay().getRealMetrics(realMetrics);

        float screenWidthCms = screenWidthPixels / realMetrics.xdpi * 2.54f;
        float screenHeightCms = screenHeightPixels / realMetrics.ydpi * 2.54f;

        int width_gaze_num = (int) (screenWidthCms / 2 / GAZE_POINT_DISTANCE_CM) - 1;
        int height_gaze_num = (int) (screenHeightCms / 2 / GAZE_POINT_DISTANCE_CM) - 1;

        for (int i = 0; i < width_gaze_num * 2 + 1; i++)
        {
            for (int j = 0; j < height_gaze_num * 2 + 1; j++)
            {
                View gazePoint = new View(requireActivity());
                gazePoint.setLayoutParams(new ViewGroup.LayoutParams(GAZE_POINT_WIDTH, GAZE_POINT_WIDTH));
                gazePoint.setBackgroundColor(Color.BLACK);
                gazePoint.setX(screenWidthPixels / 2f + (i - width_gaze_num) * GAZE_POINT_DISTANCE_CM / 2.54f * realMetrics.xdpi - GAZE_POINT_WIDTH / 2);
                gazePoint.setY(screenHeightPixels / 2f + (j - height_gaze_num) * GAZE_POINT_DISTANCE_CM / 2.54f * realMetrics.ydpi - GAZE_POINT_WIDTH / 2);
                ViewAndName viewAndName = new ViewAndName(gazePoint, String.format("gazePoint_%d_%d", i-width_gaze_num, j-height_gaze_num));
                mViewsForGaze.add(viewAndName);
                gazePoint.setElevation(100);
                gazePoint.setOutlineProvider(null);
                gazePoint.setVisibility(View.INVISIBLE);
                ((ViewGroup) rootView).addView(gazePoint);
            }
        }

        for (int i = 0; i < mViewsForGaze.size(); i++)
        {
            mIndicesForGaze.add(i);
        }
        Collections.shuffle(mIndicesForGaze);

        mViewsForGaze.get(mIndicesForGaze.get(mGazePointIndex)).mView.setVisibility(View.VISIBLE);

        return rootView;
    }

    @Override
    public void onViewCreated(final View view, Bundle savedInstanceState)
    {
        mTextureView = view.findViewById(R.id.texture);
        mChronometer = view.findViewById(R.id.chronometer);
        mInfo = view.findViewById(R.id.info);
        mRecButtonVideo = view.findViewById(R.id.view_overlay_button);
//        mRecButtonVideo.setOnClickListener(new CustomClickListener(this));
        mRecButtonVideo.setOnTouchListener(new CustomOnTouchListener(requireContext(), this));
    }

    @Override
    public void onResume()
    {
        surfaces.clear();
        super.onResume();
        startBackgroundThread();
        if (mTextureView.isAvailable())
        {
//            DisplayMetrics displayMetrics = new DisplayMetrics();
//            requireActivity().getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
//
//            int screenWidth = displayMetrics.widthPixels;
//            int screenHeight = displayMetrics.heightPixels;
//
            openCamera(mTextureView.getWidth(), mTextureView.getHeight());
        }
        else
        {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }

    @Override
    public void onPause()
    {
        if (mIsRecordingVideo)
        {
            stopRecordingVideo();
        }
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    /**
     * Starts a background thread and its {@link Handler}.
     */
    private void startBackgroundThread()
    {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    /**
     * Stops the background thread and its {@link Handler}.
     */
    private void stopBackgroundThread()
    {
        mBackgroundThread.quitSafely();
        try
        {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        }
        catch (InterruptedException e)
        {
            e.printStackTrace();
        }
    }

    /**
     * Gets whether you should show UI with rationale for requesting permissions.
     *
     * @param permissions The permissions your app wants to request.
     * @return Whether you can show permission rationale UI.
     */
    private boolean shouldShowRequestPermissionRationale(String[] permissions)
    {
        for (String permission : permissions)
        {
            if (shouldShowRequestPermissionRationale(permission))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Requests permissions needed for recording video.
     */
    private void requestVideoPermissions()
    {
        if (shouldShowRequestPermissionRationale(VIDEO_PERMISSIONS))
        {
            ConfirmationDialog.newInstance(R.string.permission_request)
                    .setOkListener(new DialogInterface.OnClickListener()
                    {
                        @Override
                        public void onClick(DialogInterface dialog, int which)
                        {
                            requestPermissions(VIDEO_PERMISSIONS, REQUEST_VIDEO_PERMISSIONS);
                        }
                    })
                    .setCancelListener(new DialogInterface.OnClickListener()
                    {
                        @Override
                        public void onClick(DialogInterface dialog, int which)
                        {
                            getActivity().finish();
                        }
                    })
                    .show(getFragmentManager(), "TAG");
        } else
        {
            requestPermissions(VIDEO_PERMISSIONS, REQUEST_VIDEO_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults)
    {
        Log.d(TAG, "onRequestPermissionsResult");
        if (requestCode == REQUEST_VIDEO_PERMISSIONS)
        {
            if (grantResults.length == VIDEO_PERMISSIONS.length)
            {
                for (int result : grantResults)
                {
                    if (result != PackageManager.PERMISSION_GRANTED)
                    {
                        ErrorDialog.newInstance(getString(R.string.permission_request))
                                .show(getChildFragmentManager(), FRAGMENT_DIALOG);
                        break;
                    }
                }
            } else
            {
                ErrorDialog.newInstance(getString(R.string.permission_request))
                        .show(getChildFragmentManager(), FRAGMENT_DIALOG);
            }
        } else
        {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    private boolean hasPermissionsGranted(String[] permissions)
    {
        for (String permission : permissions)
        {
            if (ActivityCompat.checkSelfPermission(getActivity(), permission)
                    != PackageManager.PERMISSION_GRANTED)
            {
                return false;
            }
        }
        return true;
    }

    private void openCamera(int width, int height)
    {
        if (!hasPermissionsGranted(VIDEO_PERMISSIONS))
        {
            requestVideoPermissions();
            return;
        }
        final Activity activity = getActivity();
        if (null == activity || activity.isFinishing())
        {
            return;
        }
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try
        {
            Log.d(TAG, "tryAcquire");
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS))
            {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }

            String[] cameraIdList = Objects.requireNonNull(manager).getCameraIdList();
            String cameraId = null;
            for (String id : cameraIdList)
            {
                if (id == null)
                {
                    throw new RuntimeException("No camera available.");
                }
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);

                // 检查相机是否是前置摄像头
                Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (lensFacing != null && lensFacing == CameraCharacteristics.LENS_FACING_FRONT)
                {
                    cameraId = id;
                    break;
                }
            }

            mCameraCharacteristics = manager.getCameraCharacteristics(cameraId);

            StreamConfigurationMap map = mCameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            if (map == null)
            {
                ErrorDialog.newInstance(getString(R.string.open_failed_of_map_null)).show(getFragmentManager(), "TAG");
                return;
            }

            List<Size> normalVideoSizes = new ArrayList<>();
            Range<Integer>[] aeFpsRanges = mCameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            for (Range<Integer> fpsRange : aeFpsRanges)
            {
                if (fpsRange.getLower().equals(fpsRange.getUpper()))
                {
                    for (android.util.Size size : map.getOutputSizes(MediaRecorder.class))
                    {
                        Size videoSize = new Size(size.getWidth(), size.getHeight());
                        if (videoSize.hasHighSpeedCamcorder(CameraMetadata.LENS_FACING_FRONT))
                        {
                            videoSize.setFps(fpsRange.getUpper());
                            Log.d(TAG, "Support HighSpeed video recording for " + videoSize.toString());
                            normalVideoSizes.add(videoSize);
                        }
                    }
                }
            }

            if (normalVideoSizes.isEmpty())
            {
                ErrorDialog.newInstance(getString(R.string.open_failed_of_not_support_high_speed)).show(getFragmentManager(), "TAG");
                return;
            }

            Collections.sort(normalVideoSizes);
            mVideoSize = normalVideoSizes.get(normalVideoSizes.size() - 1);
            mPreviewSize = mVideoSize;

//            List<Size> highSpeedSizes = new ArrayList<>();
//            for (Range<Integer> fpsRange : map.getHighSpeedVideoFpsRanges())
//            {
//                if (fpsRange.getLower().equals(fpsRange.getUpper()))
//                {
//                    for (android.util.Size size : map.getHighSpeedVideoSizesFor(fpsRange))
//                    {
//                        Size videoSize = new Size(size.getWidth(), size.getHeight());
//                        if (videoSize.hasHighSpeedCamcorder(CameraMetadata.LENS_FACING_FRONT))
//                        {
//                            videoSize.setFps(fpsRange.getUpper());
//                            Log.d(TAG, "Support HighSpeed video recording for " + videoSize.toString());
//                            highSpeedSizes.add(videoSize);
//                        }
//                    }
//                }
//            }
//
//            if (highSpeedSizes.isEmpty())
//            {
//                ErrorDialog.newInstance(getString(R.string.open_failed_of_not_support_high_speed)).show(getFragmentManager(), "TAG");
//                return;
//            }
//
//            Collections.sort(highSpeedSizes);
//            mVideoSize = highSpeedSizes.get(highSpeedSizes.size() - 1);
//            mPreviewSize = mVideoSize;

            mInfo.setText(getString(R.string.video_info, mVideoSize.getWidth(), mVideoSize.getHeight(), mVideoSize.getFps()));

            int orientation = getResources().getConfiguration().orientation;
            if (orientation == Configuration.ORIENTATION_LANDSCAPE)
            {
                mTextureView.setAspectRatio(mPreviewSize.getWidth(), mPreviewSize.getHeight());
//                mTextureView.setAspectRatio(height, width);
            } else
            {
                mTextureView.setAspectRatio(mPreviewSize.getHeight(), mPreviewSize.getWidth());
//                mTextureView.setAspectRatio(width, height);
            }
            configureTransform(width, height);
            mMediaRecorder = new MediaRecorder();
            //            mMediaFormat = new MediaFormat();
            if (ActivityCompat.checkSelfPermission(getActivity(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return;
            }
            manager.openCamera(cameraId, mStateCallback, mBackgroundHandler);
        }
        catch (CameraAccessException e)
        {
            Toast.makeText(activity, "Cannot access the camera.", Toast.LENGTH_SHORT).show();
            activity.finish();
        }
        catch (NullPointerException e)
        {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            ErrorDialog.newInstance(getString(R.string.camera_error))
                    .show(getChildFragmentManager(), FRAGMENT_DIALOG);
        }
        catch (InterruptedException e)
        {
            throw new RuntimeException("Interrupted while trying to lock camera opening.");
        }
    }

    private void closeCamera()
    {
        deleteEmptyFIle(mNextVideoFilePath);
        try
        {
            mCameraOpenCloseLock.acquire();
            if (null != mCameraDevice)
            {
                mCameraDevice.close();
                mCameraDevice = null;
            }
            if (null != mMediaRecorder)
            {
                mMediaRecorder.release();
                mMediaRecorder = null;
            }
        }
        catch (Exception ignored)
        {}
        finally
        {
            mCameraOpenCloseLock.release();
        }
    }

    /**
     * Start the camera preview.
     */
    public void startPreview()
    {
//        Log.e("START_PREVIEW", String.format("%d, %d", mTextureView.getWidth(), mTextureView.getHeight()));
        if (null == mCameraDevice || !mTextureView.isAvailable() || null == mPreviewSize)
        {
            return;
        }

        if (mMediaRecorder != null)
        {
            //这个东西在上一次打开预览的时候,已经设置成了prepare,但是没有使用. 所以需要reset
            mMediaRecorder.reset();
        }

        try
        {
            surfaces.clear();
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            assert texture != null;
            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
            mPreviewBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            Surface previewSurface = new Surface(texture);
            surfaces.add(previewSurface);
            mPreviewBuilder.addTarget(previewSurface);

            setUpMediaRecorder();
            surfaces.add(mMediaRecorder.getSurface());
            mPreviewBuilder.addTarget(mMediaRecorder.getSurface());

            mCameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback()
            {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession)
                {
                    mPreviewSession = cameraCaptureSession;
                    updatePreview();
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession)
                {
                    Activity activity = getActivity();
                    if (null != activity)
                    {
                        Toast.makeText(activity, "Failed", Toast.LENGTH_SHORT).show();
                    }
                }
            }, mBackgroundHandler);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    /**
     * Update the camera preview. {@link #startPreview()} needs to be called in advance.
     */
    private void updatePreview()
    {
        if (null == mCameraDevice)
        {
            return;
        }
        try
        {
//            HandlerThread thread = new HandlerThread("*CameraHighSpeedPreview");
//            thread.start();
            setUpCaptureRequestBuilder(mPreviewBuilder);
            CaptureRequest singlePreviewRequest = mPreviewBuilder.build();
            mPreviewSession.setRepeatingRequest(singlePreviewRequest, null, mBackgroundHandler);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    private void setUpCaptureRequestBuilder(CaptureRequest.Builder builder) {
        int fps = mVideoSize.getFps();
        Range<Integer> fpsRange = Range.create(fps, fps);
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
    }

    /**
     * Configures the necessary {@link Matrix} transformation to `mTextureView`.
     * This method should not to be called until the camera preview size is determined in
     * openCamera, or until the size of `mTextureView` is fixed.
     *
     * @param viewWidth  The width of `mTextureView`
     * @param viewHeight The height of `mTextureView`
     */
    private void configureTransform(int viewWidth, int viewHeight) {
        Activity activity = getActivity();
        if (null == mTextureView || null == mPreviewSize || null == activity) {
            return;
        }
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max((float) viewHeight / mPreviewSize.getHeight(), (float) viewWidth / mPreviewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        }
        mTextureView.setTransform(matrix);
    }

    //    private MediaFormat mMediaFormat;
    private void setUpMediaRecorder() throws IOException {
        final Activity activity = getActivity();
        if (null == activity) {
            return;
        }

        CamcorderProfile profile = mVideoSize.getCamcorderProfile();
        mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mMediaRecorder.setProfile(profile);
        mNextVideoFilePath = getVideoFile();
        mMediaRecorder.setOutputFile(mNextVideoFilePath);

        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        int orientation = ORIENTATIONS.get(rotation);

        // 对于前置摄像头，需要额外检查镜像和旋转
        CameraCharacteristics characteristics = mCameraCharacteristics;
        Integer frontFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
        if (frontFacing != null && frontFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            int sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            orientation = (orientation + sensorOrientation + 270) % 360;
        }

        mMediaRecorder.setOrientationHint(orientation);
        mMediaRecorder.prepare();
    }

    /**
     * This method chooses where to save the video and what the name of the video file is
     *
     * @return path + filename
     */
    public String getVideoFile() {

        final File dcimFile = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
        final File camera2VideoImage = new File(dcimFile, "minimal_precision_data_collection");
        if (!camera2VideoImage.exists()) {
            camera2VideoImage.mkdirs();
        }
        return String.format("%s/%s_%d.mp4",
                camera2VideoImage.getAbsolutePath(),
                mViewsForGaze.get(mIndicesForGaze.get(mGazePointIndex)).mName,
                System.currentTimeMillis());

    }

    /**
     * 添加记录到媒体库
     */
    private void addToMediaStore() {
        Intent sanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
        Uri uri = Uri.fromFile(new File(mNextVideoFilePath));
        sanIntent.setData(uri);
        getContext().sendBroadcast(sanIntent);
    }

    public void startRecordingVideo() {

        mIsRecordingVideo = true;
//        mRecButtonVideo.setText(R.string.stop);
        mMediaRecorder.start();
        mChronometer.setBase(SystemClock.elapsedRealtime());
        mChronometer.start();
        mChronometer.setVisibility(View.VISIBLE);
    }

    public void stopRecordingVideo() {
//        mRecButtonVideo.setText(R.string.start);
        mChronometer.stop();
        mChronometer.setVisibility(View.GONE);
        // Stop recording
        mMediaRecorder.stop();
        mMediaRecorder.reset();
        Activity activity = getActivity();
        if (null != activity) {
            Toast.makeText(activity, "Video saved: " + mNextVideoFilePath,
                    Toast.LENGTH_SHORT).show();
        }
        Log.e(TAG, "stopRecordingVideo: [saved] = " + mNextVideoFilePath);

        addToMediaStore();
        // UI
        mIsRecordingVideo = false;
    }


    /**
     * 如果传入的文件为空文件(文件存在,但大小为0kb),则删除掉
     * 这些0长度的文件, 是因为MediaRecorder调用了prepare之后没有开始录制生成的. 他们数据无效数据
     *
     * @param filePath 文件路径
     */
    public static void deleteEmptyFIle(String filePath) {
        if (TextUtils.isEmpty(filePath)) {
            return;
        }
        File file = new File(filePath);
        if (file.isFile() && file.length() <= 0) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }
}
