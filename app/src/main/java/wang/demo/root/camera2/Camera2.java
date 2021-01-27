package wang.demo.root.camera2;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import wang.demo.root.R;

public class Camera2 extends Activity {

    private static final String TAG = "Camera2";

    private static final String CAMERA_BACK = "0";

    private static final String CAMERA_FRONT = "1";

    private CameraManager manager;
    private CameraDevice device;
    private String cameraId = CAMERA_BACK;
    private Size mPreviewSize = new Size(1080,1920);
    private CameraCharacteristics cameraCharacteristics;
    private int mSensorOrientation;
    private Handler handler;
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);
    private TextureView textureView;
    private HandlerThread handlerThread;
    private CaptureRequest.Builder mCaptureRequestBuilder;
    private CaptureRequest mCaptureRequest;
    private CameraCaptureSession mCameraCaptureSession;
    private Button video_button,pic_button;
    private Looper looper;
    private String mState;
    private ImageReader mReader;

    @Override
    protected void onCreate(Bundle saved){
        super.onCreate(saved);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.camera2_preview);
        textureView = findViewById(R.id.preview);
        video_button = findViewById(R.id.video_camera);
        video_button.setOnClickListener(new MyButtonListener());
        video_button.setTag(1);
        pic_button = findViewById(R.id.pic_camera);
        pic_button.setOnClickListener(new MyButtonListener());
        pic_button.setTag(2);
    }

    public class MyButtonListener implements View.OnClickListener{

        @Override
        public void onClick(View v){
                int tag =(Integer) v.getTag();
                switch (tag){
                    case 1:startVideo();break;
                    case 2:takePicture();break;
                }
        }
    }

    @Override
    protected void onResume(){
        super.onResume();
        createPreviewThread();
        if(textureView.isAvailable()){//texture可用时打开相机，否则添加监控
                openCamera();
        }else{
            textureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }

    }

    @Override
    protected void onPause(){
        Log.d("wc","pause");
        closeCamera();
//        stopPreviewThread();不停的刷sending message to a Handler on a dead thread
//        没搞清楚！
        super.onPause();
    }

    /**
     监听textureview可用时，打开相机
     */
    TextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            openCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        }
        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return false;
        }
        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        }
    };

    /**
     获得相机服务
     */
    private void getService(){
        manager = (CameraManager)getSystemService(CAMERA_SERVICE);
    }

    /**
     打开相机
     */
    private void openCamera() {
        getService();
        if (ContextCompat.checkSelfPermission(Camera2.this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            return;//自我权限检查
        }
        try{
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            manager.openCamera(cameraId, stateCallback, handler); //打开相机
        }
        catch (CameraAccessException e){
            e.printStackTrace();
        }catch (InterruptedException e){
            throw new RuntimeException("");
        }
    }

    CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            setupImageReader();
            mCameraOpenCloseLock.release();
            device = camera;
            startpreview();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            mCameraOpenCloseLock.release();
            if (device != null) {
                device.close();
                camera.close();
                device = null;
            }
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            mCameraOpenCloseLock.release();
            if (device != null) {
                device.close();
                camera.close();
                device = null;
            }
        }
    };

    /**
     *关闭相机
     */
    private void closeCamera(){
        try {
            mCameraOpenCloseLock.acquire();
        }catch (Exception e){e.printStackTrace();}
        finally {
            mCameraOpenCloseLock.release();
        }
    }

    /**
     * 后台新建预览线程
     */
    private void createPreviewThread(){
        handlerThread = new HandlerThread("camera2_1");
        handlerThread.start();
        looper = handlerThread.getLooper();
        handler = new Handler(looper);
    }

    /**
    /**
     * 结束线程
     */
    private void stopPreviewThread(){
        handlerThread.quitSafely();
        try{
            handlerThread.quit();
            handlerThread.interrupt();
            handlerThread = null;
            handler = null;
        }catch (Exception e){}

    }

    /**
     * 开始预览
     */
    private void startpreview() {
        SurfaceTexture mSurfaceTexture = textureView.getSurfaceTexture();
        mSurfaceTexture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
        Surface surface = new Surface(mSurfaceTexture);
        try {
            mCaptureRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);//预览请求
            mCaptureRequestBuilder.addTarget(surface);
            mCaptureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            device.createCaptureSession(Arrays.asList(surface,mReader.getSurface()), new CameraCaptureSession.StateCallback() {//打开预览
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
//                    toast("开启预览成功");
                    mCameraCaptureSession = session;
                    mCaptureRequest = mCaptureRequestBuilder.build();
                    try {
                        mCameraCaptureSession.setRepeatingRequest(mCaptureRequestBuilder.build(), null, handler);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    toast("开启预览失败！");
                }
            }, handler);
        }catch (CameraAccessException e){
            e.printStackTrace();
        }
    }

    private CameraCaptureSession.CaptureCallback mCaptureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);
            logd("onCaptureCompleted"+result.get(CaptureResult.CONTROL_AF_STATE));
        }
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (device != null) {
            device.close();
        }
        if (mCameraCaptureSession != null) {
            mCameraCaptureSession = null;
        }

    }

    private void takePicture(){
        Log.d(TAG,"takePicture");
        try {
                logd("here");
                mCaptureRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                mCaptureRequestBuilder.addTarget(mReader.getSurface());
                logd(mReader.getSurface().toString());
                mCameraCaptureSession.capture(mCaptureRequestBuilder.build(),mCaptureCallback,handler);

        }catch (Exception e){e.printStackTrace();}
    }

    private void startVideo(){
        Log.d(TAG,"startVdeo");
    }

    private void setupImageReader(){
        mReader = ImageReader.newInstance(1080,1920,
                ImageFormat.JPEG, /*maxImages*/2);
        logd(mReader.toString()+","+mReader.getSurface().toString());
        mReader.setOnImageAvailableListener(
                mOnImageAvailableListener, handler);
    }


    private void savePicture(){
        logd("savepic success!~");
    }
    static class CompareSizesBArea implements Comparator<Size> {

        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }

    }

    ImageReader.OnImageAvailableListener mOnImageAvailableListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            toast("拍照");
            Image image = reader.acquireLatestImage();
            new Thread(new ImageSaver(image)).start();
            resetartPreview();
        }
    };
    private static class ImageSaver implements Runnable {//img转换bitmap进行存储

        /**
         * The JPEG image
         */
        private final Image mImage;
        /**
         * The file we save the image into.
         */
        private File mFile;
        public ImageSaver(Image image) {
            mImage = image;

        }
        @Override
        public void run() {
            ByteBuffer buffer = mImage.getPlanes()[0].getBuffer();
            mFile = new File("sdcard/DCIM/myPicture.jpg");
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            FileOutputStream output = null;
            try {
                output = new FileOutputStream(mFile);
                output.write(bytes);
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                mImage.close();
                if (null != output) {
                    try {
                        output.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

    }

    private void resetartPreview(){
        try {
            logd("restartPreview");
            //执行setRepeatingRequest方法就行了，注意mCaptureRequest是之前开启预览设置的请求
            mCameraCaptureSession.setRepeatingRequest(mCaptureRequest, null, handler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void logd(String msg){
        Log.d(TAG,msg);
    }

    private void toast(String str){
        Toast.makeText(getBaseContext(),str,Toast.LENGTH_SHORT).show();
    }
}
