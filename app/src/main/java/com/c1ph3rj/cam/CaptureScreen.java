package com.c1ph3rj.cam;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.Settings;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class CaptureScreen extends AppCompatActivity {
    private static final long FRAME_RATE_INTERVAL = 100; // Milliseconds
    private long lastCaptureTime = 0;
    private static final int PERMISSIONS_REQUEST = 99;
    private static final String PERMISSION_CAMERA = android.Manifest.permission.CAMERA;
    private static final String PERMISSION_STORAGE = android.Manifest.permission.WRITE_EXTERNAL_STORAGE;
    private static final String PERMISSION_READ_STORAGE = android.Manifest.permission.READ_EXTERNAL_STORAGE;
    private static final String PERMISSION_LOCATION = Manifest.permission.ACCESS_FINE_LOCATION;
    TextureView camPreview;
    CardView captureBtn;
    CardView galleryLayout;
    ImageView capturedPreview;
    TextView capturedImagesCount;
    private File FILE_SAVE_LOCATION;
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    String cameraID;
    CameraDevice cameraDevice;
    CameraCaptureSession cameraCaptureSession;
    CaptureRequest.Builder captureRequestBuilder;
    Size imageDimensions;
    ImageReader imageReader;
    File file;
    File folder;
    String folderName = "CapturedDocs";
    Handler mBackgroundHandler;
    HandlerThread mBackgroundHandlerThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_capture_screen);

        init();
    }

    void init() {
        try {
            camPreview = findViewById(R.id.camPreview);
            captureBtn = findViewById(R.id.captureBtn);
            galleryLayout = findViewById(R.id.capturedImageLayout);
            capturedPreview = findViewById(R.id.capturedImageView);
            capturedImagesCount = findViewById(R.id.countView);
            FILE_SAVE_LOCATION = getExternalFilesDir(folderName);

            checkImagePreviewLayoutVisibility();

            if (hasPermission()) {
                initCam();
            } else {
                requestPermission();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void initCam() {
        try {
            camPreview.setSurfaceTextureListener(textureListener);
            captureBtn.setOnClickListener(onClickCapture -> takePicture());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void takePicture() {
        try {
            if (cameraDevice == null) {
                Toast.makeText(this, "Something went wrong!", Toast.LENGTH_SHORT).show();
                return;
            }
            CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

            try {
                CameraCharacteristics cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraDevice.getId());
                Size[] jpegSizes = null;
                if (cameraCharacteristics != null) {
                    jpegSizes = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.JPEG);
                }

                int width = 640;
                int height = 480;
                if (jpegSizes != null && jpegSizes.length > 0) {
                    width = jpegSizes[0].getWidth();
                    height = jpegSizes[0].getHeight();
                }

                imageReader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 1);
                List<Surface> outputSurfaces = new ArrayList<>(2);
                outputSurfaces.add(imageReader.getSurface());
                outputSurfaces.add(new Surface(camPreview.getSurfaceTexture()));
                final CaptureRequest.Builder captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                captureRequestBuilder.addTarget(imageReader.getSurface());
                captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                int rotation = getWindowManager().getDefaultDisplay().getRotation();
                captureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS.get(rotation));
                file = null;
                folder = new File(folderName);
                String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
                String imageFileName = "IMG_" + timeStamp + ".jpeg";
                file = new File(getExternalFilesDir(folderName) + "/" + imageFileName);
                if (!folder.exists()) {
                    if (folder.mkdirs()) {
                        System.out.println("Folder Created SuccessFully!");
                    }
                }
                ImageReader.OnImageAvailableListener imageAvailableListener = imageReader -> {
                    try (Image image = imageReader.acquireLatestImage()) {
                        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                        byte[] bytes = new byte[buffer.capacity()];
                        buffer.get(bytes);
                        save(bytes);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                };
                imageReader.setOnImageAvailableListener(imageAvailableListener, mBackgroundHandler);
                final CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {
                    @Override
                    public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                        super.onCaptureCompleted(session, request, result);
                        Toast.makeText(CaptureScreen.this, "Saved File: " + file, Toast.LENGTH_SHORT).show();
                        createCamPreview();
                    }
                };

                cameraDevice.createCaptureSession(outputSurfaces, new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                        try {
                            cameraCaptureSession.capture(captureRequestBuilder.build(), captureCallback, mBackgroundHandler);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }

                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {

                    }
                }, mBackgroundHandler);
            } catch (Exception e) {
                e.printStackTrace();
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void save(byte[] bytes) {
        OutputStream output;
        try {
            output = new FileOutputStream(file);
            output.write(bytes);
            checkImagePreviewLayoutVisibility();
            output.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surfaceTexture, int i, int i1) {
            openCam();
        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surfaceTexture, int i, int i1) {

        }

        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surfaceTexture) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surfaceTexture) {
            updatePreview();
        }
    };

    CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            CaptureScreen.this.cameraDevice = cameraDevice;
            createCamPreview();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            CaptureScreen.this.cameraDevice.close();
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int i) {
            CaptureScreen.this.cameraDevice.close();
            CaptureScreen.this.cameraDevice = null;
        }
    };

    private void startBackgroundThread() {
        try {
            mBackgroundHandlerThread = new HandlerThread("background_Thread");
            mBackgroundHandlerThread.start();
            mBackgroundHandler = new Handler(mBackgroundHandlerThread.getLooper());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void stopBackgroundThread() {
        try {
            mBackgroundHandlerThread.quitSafely();
            mBackgroundHandlerThread.join();
            mBackgroundHandler = null;
            mBackgroundHandlerThread = null;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void createCamPreview() {
        try {
            SurfaceTexture texture = camPreview.getSurfaceTexture();
            if (texture != null) {
                texture.setDefaultBufferSize(imageDimensions.getWidth(), imageDimensions.getHeight());
                Surface surface = new Surface(texture);
                captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                captureRequestBuilder.addTarget(surface);
                cameraDevice.createCaptureSession(Collections.singletonList(surface), new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                        if (cameraDevice == null) {
                            return;
                        }

                        CaptureScreen.this.cameraCaptureSession = cameraCaptureSession;
                        updatePreview();
                    }

                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {

                    }
                }, null);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void updatePreview() {
        try {
            if (cameraDevice == null) {
                return;
            }
            captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            try {
                if (System.currentTimeMillis() - lastCaptureTime >= FRAME_RATE_INTERVAL) {
                    lastCaptureTime = System.currentTimeMillis();
                    cameraCaptureSession.capture(captureRequestBuilder.build(), null, mBackgroundHandler);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @SuppressLint("MissingPermission")
    private void openCam() {
        try {
            CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            cameraID = cameraManager.getCameraIdList()[0];
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraID);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) {
                // Choose an appropriate size for the preview
                Size[] outputSizes = map.getOutputSizes(SurfaceTexture.class);
                for (Size size : outputSizes) {
                    if (size.getWidth() <= 640 && size.getHeight() <= 480) { // Adjust the dimensions as needed
                        imageDimensions = size;
                        break;
                    }
                }

                // Set the default buffer size for the TextureView
                camPreview.getSurfaceTexture().setDefaultBufferSize(imageDimensions.getWidth(), imageDimensions.getHeight());

                cameraManager.openCamera(cameraID, stateCallback, null);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    private boolean hasPermission() {
        return checkSelfPermission(PERMISSION_CAMERA) ==
                PackageManager.PERMISSION_GRANTED && checkSelfPermission(PERMISSION_LOCATION) ==
                PackageManager.PERMISSION_GRANTED && checkSelfPermission(PERMISSION_STORAGE) ==
                PackageManager.PERMISSION_GRANTED && checkSelfPermission(PERMISSION_READ_STORAGE) ==
                PackageManager.PERMISSION_GRANTED;
    }


    private void requestPermission() {
        try {
            if (shouldShowRequestPermissionRationale(PERMISSION_CAMERA) ||
                    shouldShowRequestPermissionRationale(PERMISSION_STORAGE) ||
                    shouldShowRequestPermissionRationale(PERMISSION_READ_STORAGE) ||
                    shouldShowRequestPermissionRationale(PERMISSION_LOCATION) ||
                    shouldShowRequestPermissionRationale(PERMISSION_CAMERA)) {
                Toast.makeText(this, "Camera, Location and storage permissions are required", Toast.LENGTH_LONG).show();
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                requestPermissions(new String[]{PERMISSION_LOCATION, PERMISSION_CAMERA, PERMISSION_STORAGE, PERMISSION_READ_STORAGE}, PERMISSIONS_REQUEST);
            } else {
                requestPermissions(new String[]{PERMISSION_LOCATION, PERMISSION_CAMERA, PERMISSION_STORAGE, PERMISSION_READ_STORAGE}, PERMISSIONS_REQUEST);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSIONS_REQUEST) {
            boolean allPermissionsGranted = true;
            for (int grantResult : grantResults) {
                if (grantResult != PackageManager.PERMISSION_GRANTED) {
                    allPermissionsGranted = false;
                    break;
                }
            }
            if (!allPermissionsGranted) {
                // Redirect to app settings permissions page
                AlertDialog.Builder dialog = new AlertDialog.Builder(this);
                dialog.setTitle(R.string.permissions_required_title);
                dialog.setMessage(R.string.permissions_required);
                dialog.setPositiveButton("Ok", (dialogInterface, i) -> {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    Uri uri = Uri.fromParts("package", getPackageName(), null);
                    intent.setData(uri);
                    startActivity(intent);
                });
                dialog.setCancelable(false);
                dialog.show();
            } else {
                initCam();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            startBackgroundThread();
            if (camPreview.isAvailable()) {
                openCam();
            } else {
                camPreview.setSurfaceTextureListener(textureListener);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onPause() {
        stopBackgroundThread();
        super.onPause();
    }

    private void checkImagePreviewLayoutVisibility() {
        if (hasFilesInDirectory(FILE_SAVE_LOCATION)) {
            galleryLayout.setVisibility(View.VISIBLE);
            capturedImagesCount.setText(String.valueOf(getFileCountInDirectory(FILE_SAVE_LOCATION)));
            capturedPreview.setImageBitmap(createBitmapFromFile(getMostRecentFile(FILE_SAVE_LOCATION)));
            if (getFileCountInDirectory(FILE_SAVE_LOCATION) > 0) {
                galleryLayout.setVisibility(View.VISIBLE);
            } else {
                galleryLayout.setVisibility(View.GONE);
            }
        } else {
            galleryLayout.setVisibility(View.GONE);
        }
    }

    private Bitmap createBitmapFromFile(File file) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888; // Adjust the configuration as needed

        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    private int getFileCountInDirectory(File directory) {
        if (directory.exists() && directory.isDirectory()) {
            File[] files = directory.listFiles();
            if (files != null) {
                return files.length;
            }
        }
        return 0;
    }

    private boolean hasFilesInDirectory(File directory) {
        if (directory.exists() && directory.isDirectory()) {
            File[] files = directory.listFiles();
            return files != null && files.length > 0;
        }
        return false;
    }

    private File getMostRecentFile(File directory) {
        File mostRecentFile = null;

        if (directory.exists() && directory.isDirectory()) {
            File[] files = directory.listFiles();

            if (files != null && files.length > 0) {
                long maxTimestamp = Long.MIN_VALUE;

                for (File file : files) {
                    if (file.isFile() && file.lastModified() > maxTimestamp) {
                        mostRecentFile = file;
                        maxTimestamp = file.lastModified();
                    }
                }
            }
        }

        return mostRecentFile;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeCamera();
    }

    private void closeCamera() {
        if (null != cameraDevice) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (null != imageReader) {
            imageReader.close();
            imageReader = null;
        }
    }

}
