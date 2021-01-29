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
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.DisplayMetrics;
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
    private Handler handler;
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);
    private TextureView textureView;
    private HandlerThread handlerThread;
    private CaptureRequest.Builder mCapturePreviewRequestBuilder,mCapturePictureRequestBuilder,
            mCaptureVideoRequestBuilder;
    private CaptureRequest mCaptureRequest;
    private CameraCaptureSession mCameraCaptureSession;
    private Button video_button,pic_button;
    private Looper looper;
    private ImageReader mReader;
    private MediaRecorder mMediaRecorder;

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
        createPreviewThread();//camera2开启一个新线程
        if(textureView.isAvailable()){//texture可用时打开相机，否则添加监控
                openCamera();
        }else{
            textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
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
            });
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

    public void getService(){
        manager = (CameraManager)getSystemService(CAMERA_SERVICE);
    }
    /**
     打开相机
     */
    private void openCamera() {
        getService();//拿服务
        if (ContextCompat.checkSelfPermission(Camera2.this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            return;//自我权限检查
        }
        try{
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {//打开相机cameraid设置为前摄
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    setupImageReader();//设置图片保存格式
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
            }, handler); //打开相机
        }
        catch (CameraAccessException e){
            e.printStackTrace();
        }catch (InterruptedException e){
            throw new RuntimeException("");
        }
    }
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
            mCapturePreviewRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);//预览请求
            mCapturePreviewRequestBuilder.addTarget(surface);
            mCapturePreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            device.createCaptureSession(Arrays.asList(surface,mReader.getSurface()), new CameraCaptureSession.StateCallback() {//打开预览
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    toast("开启预览成功");
                    mCameraCaptureSession = session;
                    mCaptureRequest = mCapturePreviewRequestBuilder.build();
                    try {
                        mCameraCaptureSession.setRepeatingRequest(mCapturePreviewRequestBuilder.build(), mCaptureCallback, handler);
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
            mCapturePictureRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            mCapturePictureRequestBuilder.addTarget(mReader.getSurface());
                logd(mReader.getSurface().toString());
                mCameraCaptureSession.capture(mCapturePictureRequestBuilder.build(),mCaptureCallback,handler);//拍照回调

        }catch (Exception e){e.printStackTrace();}
    }

    private void recordconfig() {
        SurfaceTexture mSurfaceTexture = textureView.getSurfaceTexture();
        mSurfaceTexture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
        Surface surface = new Surface(mSurfaceTexture);
        try {
            mCaptureVideoRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);//预览请求
            mCaptureVideoRequestBuilder.addTarget(surface);
            mCaptureVideoRequestBuilder.addTarget(mMediaRecorder.getSurface());
            mCaptureVideoRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            device.createCaptureSession(Arrays.asList(surface,mMediaRecorder.getSurface()), new CameraCaptureSession.StateCallback() {//打开预览
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    toast("开启录制");
                    mCameraCaptureSession = session;
                    try {
                        mCameraCaptureSession.setRepeatingRequest(mCaptureVideoRequestBuilder.build(), mCaptureCallback, handler);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    toast("录制失败！");
                }
            }, handler);
        }catch (CameraAccessException e){
            e.printStackTrace();
        }
    }

    public void initMediaRecordConfig(){
        mMediaRecorder = new MediaRecorder();
        mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);//设置音频来源
        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);//设置视频来源
        mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.DEFAULT);//设置输出格式
        mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);//设置音频编码格式，请注意这里使用默认，实际app项目需要考虑兼容问题，应该选择AAC
        mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);//设置视频编码格式，请注意这里使用默认，实际app项目需要考虑兼容问题，应该选择H264
        mMediaRecorder.setVideoEncodingBitRate(8*1024*1920);//设置比特率 一般是 1*分辨率 到 10*分辨率 之间波动。比特率越大视频越清晰但是视频文件也越大。
        mMediaRecorder.setVideoFrameRate(30);//设置帧数 选择 30即可， 过大帧数也会让视频文件更大当然也会更流畅，但是没有多少实际提升。人眼极限也就30帧了。
        Size size = getMatchingSize2();
        mMediaRecorder.setVideoSize(size.getWidth(),size.getHeight());
        mMediaRecorder.setOrientationHint(90);
        mMediaRecorder.setPreviewDisplay(new Surface(textureView.getSurfaceTexture()));
        File file = new File("sdcard/DCIM/myVideo.mp4");
        mMediaRecorder.setOutputFile(file);
        try {
            mMediaRecorder.prepare();
        }catch (Exception e){e.printStackTrace();}
        recordconfig();
    }

    public void startVideo(){
        if(video_button.getText().equals("录像")) {
            video_button.setText("停止");
            initMediaRecordConfig();
            mMediaRecorder.start();
        }else{
            video_button.setText("录像");
            mMediaRecorder.reset();
            mMediaRecorder.release();
            mMediaRecorder = null;
            startpreview();
        }
    }

    private void setupImageReader(){
        mReader = ImageReader.newInstance(1080,1920,
                ImageFormat.JPEG, /*maxImages*/2);
        logd(mReader.toString()+","+mReader.getSurface().toString());
        mReader.setOnImageAvailableListener(
                mOnImageAvailableListener, handler);
    }

    ImageReader.OnImageAvailableListener mOnImageAvailableListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            toast("拍照");
            Image image = reader.acquireLatestImage();
            new Thread(new ImageSaver(image)).start();//启动新线程保存图片
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

    private void logd(String msg){
        Log.d(TAG,msg);
    }

    private void toast(String str){
        Toast.makeText(getBaseContext(),str,Toast.LENGTH_SHORT).show();
    }

    private Size getMatchingSize2(){
        Size selectSize = null;
        try {
            CameraCharacteristics cameraCharacteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap streamConfigurationMap = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] sizes = streamConfigurationMap.getOutputSizes(ImageFormat.JPEG);
            DisplayMetrics displayMetrics = getResources().getDisplayMetrics(); //因为我这里是将预览铺满屏幕,所以直接获取屏幕分辨率
            int deviceWidth = displayMetrics.widthPixels; //屏幕分辨率宽
            int deviceHeigh = displayMetrics.heightPixels; //屏幕分辨率高
            Log.e(TAG, "getMatchingSize2: 屏幕密度宽度="+deviceWidth);
            Log.e(TAG, "getMatchingSize2: 屏幕密度高度="+deviceHeigh );
            /**
             * 循环40次,让宽度范围从最小逐步增加,找到最符合屏幕宽度的分辨率,
             * 你要是不放心那就增加循环,肯定会找到一个分辨率,不会出现此方法返回一个null的Size的情况
             * ,但是循环越大后获取的分辨率就越不匹配
             */
            for (int j = 1; j < 41; j++) {
                for (int i = 0; i < sizes.length; i++) { //遍历所有Size
                    Size itemSize = sizes[i];
                    Log.e(TAG,"当前itemSize 宽="+itemSize.getWidth()+"高="+itemSize.getHeight());
                    //判断当前Size高度小于屏幕宽度+j*5  &&  判断当前Size高度大于屏幕宽度-j*5  &&  判断当前Size宽度小于当前屏幕高度
                    if (itemSize.getHeight() < (deviceWidth + j*5) && itemSize.getHeight() > (deviceWidth - j*5)) {
                        if (selectSize != null){ //如果之前已经找到一个匹配的宽度
                            if (Math.abs(deviceHeigh-itemSize.getWidth()) < Math.abs(deviceHeigh - selectSize.getWidth())){ //求绝对值算出最接近设备高度的尺寸
                                selectSize = itemSize;
                                continue;
                            }
                        }else {
                            selectSize = itemSize;
                        }

                    }
                }
                if (selectSize != null){ //如果不等于null 说明已经找到了 跳出循环
                    break;
                }
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        Log.e(TAG, "getMatchingSize2: 选择的分辨率宽度="+selectSize.getWidth());
        Log.e(TAG, "getMatchingSize2: 选择的分辨率高度="+selectSize.getHeight());
        return selectSize;
    }
}
//test