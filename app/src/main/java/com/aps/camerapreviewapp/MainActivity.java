package com.aps.camerapreviewapp;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.graphics.BitmapCompat;


import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;

import android.content.Intent;
import android.content.pm.PackageManager;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;

import android.graphics.drawable.BitmapDrawable;
import android.hardware.Camera;
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
import android.media.AudioFormat;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;


import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.widget.Chronometer;
import android.widget.ImageButton;
import android.widget.Toast;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;


@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class MainActivity extends AppCompatActivity {


        private static final String TAG = "MainActivity";
        private TextureView mTextureView;
        private String mCameraId;
        protected CameraDevice mCameraDevice;
        protected CameraCaptureSession mCameraCaptureSessions;
        protected CaptureRequest.Builder mCaptureRequestBuilder;
        private Size imageDimension;
        private ImageReader imageReader;
        private static final int REQUEST_CAMERA_PERMISSION = 200;
        private Handler mBackgroundHandler;
        private HandlerThread mBackgroundThread;
        private SurfaceView mCamV;



    private static final int Width = 1280;
    private static final int Height = 720;
    private static final int Bitrate =10000;
    private static final int Framerate = 15;


    private RingBuffer mRingBuffer = new RingBuffer();
    private EncDecThread mEncDecThread = null;
    private boolean mResumed = false;

    @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            setContentView(R.layout.activity_main);

            mTextureView = (TextureView) findViewById(R.id.cameraVeiw);
            mCamV = (SurfaceView) findViewById(R.id.camera);
            assert mTextureView != null;
            mTextureView.setSurfaceTextureListener(textureListener);
mCamV.getHolder().addCallback(new SurfaceHolder.Callback() {
    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.d("MainActivity", "mDecodeSurface surfaceChanged");


        if (mEncDecThread != null) {
            mEncDecThread.finish();
        }
        if (mResumed) {
            mEncDecThread = new EncDecThread();
            mEncDecThread.start();
        }
    }
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.d("MainActivity", "mDecodeSurface surfaceCreated");
    }
    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.d("MainActivity", "mDecodeSurface surfaceDestroyed");
    }
});



    }

        TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                //open your camera here
                openCamera();

            }
            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                // Transform you image captured size according to the surface width and height
            }
            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                return false;
            }
            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                Bitmap frame = Bitmap.createBitmap(mTextureView.getWidth(), mTextureView.getHeight(), Bitmap.Config.ARGB_8888);


                final int lnth=frame.getByteCount();

                ByteBuffer dst= ByteBuffer.allocate(lnth);
                frame.copyPixelsToBuffer( dst);
               byte [] barray =dst.array();
                mRingBuffer.set(barray);
                mRingBuffer.release(barray);

            }
        };


        private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
            @Override
            public void onOpened(CameraDevice camera) {
                //This is called when the camera is open
                Log.e(TAG, "onOpened");
                mCameraDevice = camera;

                createCameraPreview();
            }
            @Override
            public void onDisconnected(CameraDevice camera) {
                mCameraDevice.close();
            }
            @Override
            public void onError(CameraDevice camera, int error) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
        };
        /*final CameraCaptureSession.CaptureCallback captureCallbackListener = new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                super.onCaptureCompleted(session, request, result);
                createCameraPreview();
            }
        };*/
        protected void startBackgroundThread() {
            mBackgroundThread = new HandlerThread("Camera Background");
            mBackgroundThread.start();
            mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
        }
        protected void stopBackgroundThread() {
            mBackgroundThread.quitSafely();
            try {
                mBackgroundThread.join();
                mBackgroundThread = null;
                mBackgroundHandler = null;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

    protected void createCameraPreview() {
        try {
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            assert texture != null;
            texture.setDefaultBufferSize(imageDimension.getWidth(), imageDimension.getHeight());
            Surface surface = new Surface(texture);
            ImageReader reader = ImageReader.newInstance(mTextureView.getWidth(), mTextureView.getHeight(), ImageFormat.YUV_420_888, 1);
            List<Surface> outputSurfaces = new ArrayList<Surface>(2);
            outputSurfaces.add(reader.getSurface());
            outputSurfaces.add(new Surface(mTextureView.getSurfaceTexture()));
            mCaptureRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mCaptureRequestBuilder.addTarget(surface);

            ImageReader.OnImageAvailableListener readerListener = new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    Image image = null;
                    try {
                        image = reader.acquireLatestImage();
                        Log.e("image","============"+image);
                        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                        byte[] bytes = new byte[buffer.capacity()];
                        buffer.get(bytes);
                        //save(bytes);
                    } finally {
                        if (image != null) {
                            image.close();
                        }
                    }
                }

            };

            reader.setOnImageAvailableListener(readerListener, mBackgroundHandler);
            mCameraDevice.createCaptureSession(Arrays.asList(surface), new CameraCaptureSession.StateCallback(){
                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    //The camera is already closed
                    if (null == mCameraDevice) {
                        return;
                    }
                    // When the session is ready, we start displaying the preview.
                    mCameraCaptureSessions = cameraCaptureSession;
                   /* mRingBuffer.setCallback(new RingBuffer.Callback() {
                        @Override
                        public void onBufferRelease(byte[] buffer) {
                            if (mCameraDevice != null) {
                                createCameraPreview();
                            }
                        }
                    });*/
                    updatePreview();
                }
                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                    Toast.makeText(MainActivity.this, "Configuration change", Toast.LENGTH_SHORT).show();
                }
            }, mBackgroundHandler);

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }


    private void openCamera() {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        Log.e(TAG, "is camera open");
        try {
            mCameraId = manager.getCameraIdList()[0];
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(mCameraId);

            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            assert map != null;
            imageDimension = map.getOutputSizes(SurfaceTexture.class)[0];
            // Add permission for camera and let user grant the permission
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_CAMERA_PERMISSION);
                return;
            }
            manager.openCamera(mCameraId, stateCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        Log.e(TAG, "openCamera");
    }
    protected void updatePreview() {
        if(null == mCameraDevice) {
            Log.e(TAG, "updatePreview error, return");
        }
        mCaptureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        try {
            mCameraCaptureSessions.setRepeatingRequest(mCaptureRequestBuilder.build(), null, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }
    private void closeCamera() {
        if (null != mCameraDevice) {
            mCameraDevice.close();
            mCameraDevice = null;
        }
        if (null != imageReader) {
            imageReader.close();
            imageReader = null;
        }
    }
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                // close the app
                Toast.makeText(MainActivity.this, "Sorry!!!, you can't use this app without granting permission", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }
    @Override
    protected void onResume() {
        super.onResume();
        Log.e(TAG, "onResume");
        startBackgroundThread();
        mResumed=true;
        if (mTextureView.isAvailable()) {
            openCamera();
        } else {
            mTextureView.setSurfaceTextureListener(textureListener);
        }
        if (mCamV.getHolder().getSurface().isValid()) {
            Log.d("MainActivity", "mDecodeSurface is valid");


            if (mEncDecThread != null) {
                mEncDecThread.finish();
            }
            mEncDecThread = new EncDecThread();
            mEncDecThread.start();
        } else {
            Log.d("MainActivity", "mDecodeSurface is invalid");
        }

    }
    @Override
    protected void onPause() {
        Log.e(TAG, "onPause");
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }
    public static class RingBuffer {
        private static final int Length = 8;
        private int mSetPos = 0;
        private int mGetPos = 0;
        private byte[][] mArray = null;
        private Callback mCallback = null;
        public RingBuffer() {
            mArray = new byte[Length][];
        }
        public synchronized void setCallback(Callback callback) {
            mCallback = callback;
        }
        public synchronized void set(byte buffer[]) {
            int index = mSetPos % Length;
            byte[] old_buffer = mArray[index];
            mArray[index] = buffer;
            mSetPos++;
            if (old_buffer != null) {
                  Log.d("MainActivity", "set is faster than get.");
                if (mCallback != null) {
                    mCallback.onBufferRelease(old_buffer);
                }

                mGetPos++;
            }
        }
        public synchronized byte[] get() {
            if (mGetPos >= mSetPos) {
                Log.d("MainActivity", "set is slower than get.");
                return null;
            }
            int index = mGetPos % Length;
            byte[] buffer = mArray[index];
            mArray[index] = null;
            mGetPos++;
            return buffer;
        }
        public synchronized void release(byte[] buffer) {
            if (mCallback != null && buffer != null) {
                mCallback.onBufferRelease(buffer);
            }
        }
        public abstract static class Callback {
            public abstract void onBufferRelease(byte[] buffer);
        }
    }
    private class EncDecThread extends Thread {

        private boolean mForceInputEOS = false;

        @Override
        public void run() {

            startEncodeDecodeVideo();
        }

        public void finish() {

            mForceInputEOS = true;
            try {
                join();
            } catch (Exception e) {
            }
        }

        private void startEncodeDecodeVideo() {
            int width = Width, height = Height;
            int bitRate = Bitrate;
            int frameRate = Framerate;
            String mimeType = "video/avc";
            Log.d("MainActivity", "not encoder"+mimeType);
            Surface surface = mCamV.getHolder().getSurface();
            //Surface surface = null;

            MediaCodec encoder = null, decoder = null;
            ByteBuffer[] encoderInputBuffers;
            ByteBuffer[] encoderOutputBuffers;
            ByteBuffer[] decoderInputBuffers = null;
            ByteBuffer[] decoderOutputBuffers = null;


            //Search for Codec (encoder) in h.264 (video / avc)
            int numCodecs = MediaCodecList.getCodecCount();
            MediaCodecInfo codecInfo = null;
            for (int i = 0; i < numCodecs && codecInfo == null; i++) {
                MediaCodecInfo info = MediaCodecList.getCodecInfoAt(i);
                Log.d("MainActivity", "Codec : " + info.getName());
                if (!info.isEncoder()) {
                 //   Log.d("MainActivity", "not encoder");
                    continue;
                }
                String[] types = info.getSupportedTypes();
                boolean found = false;
                for (int j = 0; j < types.length && !found; j++) {
                    if (types[j].equals(mimeType)) {
                        Log.d("MainActivity", types[j] + " found!");
                        found = true;
                    } else {
                        Log.d("MainActivity", types[j]);
                    }
                }
                if (!found)
                    continue;
                codecInfo = info;
            }
            if (codecInfo == null) {
                Log.d("MainActivity", "Encoder not found");
                return;
            }
            Log.d("MainActivity", "Using codec : " + codecInfo.getName() + " supporting " + mimeType);

            // Determine the color format to be input to the Codec (encoder)
            int colorFormat = 0;
            MediaCodecInfo.CodecCapabilities capabilities = codecInfo.getCapabilitiesForType(mimeType);
            for (int i = 0; i < capabilities.colorFormats.length /*&& colorFormat == 0*/; i++) {
                int format = capabilities.colorFormats[i];
               // Log.d("MainActivity", "Color format : " + colorFormatName(format));
                switch (format) {
                    case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar:
                    case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar:
                    case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
                    case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar:
                    case MediaCodecInfo.CodecCapabilities.COLOR_TI_FormatYUV420PackedSemiPlanar:
                        if (colorFormat == 0)
                            colorFormat = format;
                        break;
                    default:
                        break;
                }
            }
            if (colorFormat == 0) {
                Log.d("MainActivity", "No supported color format");
                return;
            }
           // Log.d("MainActivity", "Using color format : " + colorFormatName(colorFormat));

            //
            //The intent of this code is unknown
            if (codecInfo.getName().equals("OMX.TI.DUCATI1.VIDEO.H264E")) {
                // This codec doesn't support a width not a multiple of 16,
                // so round down.
                width &= ~15;
            }
            int stride = width;
            int sliceHeight = height;
            if (codecInfo.getName().startsWith("OMX.Nvidia.")) {
                stride = (stride + 15) / 16 * 16;
                sliceHeight = (sliceHeight + 15) / 16 * 16;
            }


            //Create an instance of Codec (encoder)
            try {
                encoder = MediaCodec.createByCodecName(codecInfo.getName());
            } catch (IOException e) {
                e.printStackTrace();
            }

            // Set Codec (encoder)
            MediaFormat outputFormat = MediaFormat.createVideoFormat(mimeType, width, height);
            outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
            outputFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            outputFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
            outputFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 75);
            outputFormat.setInteger("stride", stride);
            outputFormat.setInteger("slice-height", sliceHeight);
            Log.d("MainActivity", "Configuring encoder with output format : " + outputFormat);
            encoder.configure(
                    outputFormat,    //the desired format of the output data (encoder).
                    null,            //a surface on which to render the output of this decoder. (encoderのためnull指定)
                    null,            //a crypto object to facilitate secure decryption of the media data.
                    MediaCodec.CONFIGURE_FLAG_ENCODE    //configure the component as an encoder.
            );

            // Access the Codec Input and Output buffers using getInputBuffers () and getOutputBuffers ()
            encoder.start();
            encoderInputBuffers = encoder.getInputBuffers();
            encoderOutputBuffers = encoder.getOutputBuffers();


            //UseCamera generates still images when the camera is not used
            int chromaStride = stride / 2;
            int frameSize = stride * sliceHeight + 2 * chromaStride * sliceHeight / 2;


// Start encoding and decoding
            // Frame rate is 15
            // Between frames 1000000/15 = 66666us
            final long kTimeOutUs = 5000;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean sawInputEOS = false;
            boolean sawOutputEOS = false;
            MediaFormat oformat = null;
            int errors = -1;
            int numInputFrames = 0;
            int maxInputFrames = -1;
            int numOutputFrames = 0;
            float lap, lapavg = 100.0f;
            long lap0 = 0;
            int actualOutputFrame = 0;
            long actualOutputFrameLap = 0;
            actualOutputFrame = 0;
            actualOutputFrameLap = System.currentTimeMillis();
            while (!sawOutputEOS && errors < 0) {

                if (!sawInputEOS) {
                    lap = (float) (System.currentTimeMillis() - lap0); //
                  //  Average processing time per frame
                    lapavg += lap;
                    lapavg /= 2.0f;
                    float interval = 1000.0f / (float) frameRate;
                    if (interval > lapavg) {
                        try {
                            long sleep = (long) (interval - lapavg);
                            //Log.d("MainActivity", "lap0 " + lap0 + " lap " + lap + " lapavg " + lapavg + " sleep " + sleep);
                            if (sleep > 0) {
                                Thread.sleep(sleep);
                            }
                        } catch (Exception e) {
                            Log.d("MainActivity", "sleep error.");
                        }
                    }

                    byte[] inputFrame = null;
                    boolean release = false;
                   // Log.d("MainActivity", ".=======>"+ Camera.open(0));
                  if ( mRingBuffer.get()!=null) {
                        inputFrame =mRingBuffer.get();
                  // Log.d("MainActivity", ".inputFrame=======>"+ Camera.open(0));
                    }
                    if (inputFrame == null) {
                        inputFrame = mRingBuffer.get();//stillImageFrame;
                    } else {
                        release = true;
                    }
                    if (inputFrame != null) {


// Transfer ownership of the buffer from Codec to the client by calling dequeueInputBuffer () and dequeueOutputBuffer () following the Codec (encoder) start ()
                        // dequeueInputBuffer () and dequeueOutputBuffer () return indexes to access the Input and Output buffers
                        // Wait for kTimeOutUs until there is an available buffer
                        // -1 is returned if no buffer is available
                        int inputBufIndex = encoder.dequeueInputBuffer(kTimeOutUs);
//int
                        if (inputBufIndex > 0) {
                            Log.d("MainActivity", "encoder input buf index " + inputBufIndex);
                            ByteBuffer dstBuf = encoderInputBuffers[inputBufIndex];

                            int sampleSize = frameSize;
                            long presentationTimeUs = 0;


                         // Present one frame of data to Codec (encoder) using queueInputBuffer
                            // Present the BUFFER_FLAG_END_OF_STREAM flag instead of the data when the maxInputFrames frame is reached
                            if ((maxInputFrames > 0 && numInputFrames >= maxInputFrames) || mForceInputEOS) {
                                Log.d("MainActivity", "saw input.");
                                sawInputEOS = true;
                                sampleSize = 0;
                            } else {
                                dstBuf.clear();
                              //  Log.e("MainACtivity","--->"+dstBuf.get());
                                dstBuf.put(inputFrame);
                                presentationTimeUs = numInputFrames * 1000000 / frameRate;
                                numInputFrames++;
                                lap0 = System.currentTimeMillis();
                            }

                            encoder.queueInputBuffer(
                                    inputBufIndex,
                                    0 /* offset */,
                                    sampleSize,
                                    presentationTimeUs,
                                    sawInputEOS ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                        }
                    }

                    if (release) {
                        mRingBuffer.release(inputFrame);
                    }
                }

                // Get the index of the Output buffer (h.264 encoded data) from the Codec (encoder)
                //                // Get flags, offset, presentationTimeUs, size in BufferInfo (argument info)
                int res = encoder.dequeueOutputBuffer(info, kTimeOutUs);
                if (res >= 0) {
                    //Log.d("MainActivity", "encoder output buf index " + res);
                    int outputBufIndex = res;
                    ByteBuffer buf = encoderOutputBuffers[outputBufIndex];

                    //
                    //Set the read position and read upper limit of the buffer according to BufferInfo
                    buf.position(info.offset);
                    buf.limit(info.offset + info.size);

                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {


// If the Output buffer from the Codec (encoder) is the BUFFER_FLAG_CODEC_CONFIG flag instead of h.264 encoded data, set up an instance of the Codec (decoder)                        Log.d("MainActivity", "create decoder.");
                        try {
                            decoder = MediaCodec.createDecoderByType(mimeType);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        MediaFormat format = MediaFormat.createVideoFormat(mimeType, width, height);
                        format.setByteBuffer("csd-0", buf);
                        Log.d("MainActivity", "Configuring decoder with input format : " + format);
                        decoder.configure(
                                format,        //The format of the input data (decoder)
                                surface,    //a surface on which to render the output of this decoder.
                                null,        //a crypto object to facilitate secure decryption of the media data.
                                0            //configure the component as an decoder.
                        );
                        decoder.start();
                        decoderInputBuffers = decoder.getInputBuffers();
                        decoderOutputBuffers = decoder.getOutputBuffers();
                    } else {

                        //
                        //If the Output buffer from the Codec (encoder) is h.264 encoded data, input it to the Codec (decoder).
                        int decIndex = decoder.dequeueInputBuffer(-1);
                        //Log.d("MainActivity", "decoder input buf index " + decIndex);
                        decoderInputBuffers[decIndex].clear();
                        decoderInputBuffers[decIndex].put(buf);
                        decoder.queueInputBuffer(decIndex, 0, info.size, info.presentationTimeUs, info.flags);
                    }


// Return to Codec (encoder) after processing the Output buffer (h.264 encoded data) of Codec (encoder)
encoder.releaseOutputBuffer(outputBufIndex, false /* render */);
                } else if (res == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    encoderOutputBuffers = encoder.getOutputBuffers();

                    Log.d("MainActivity", "encoder output buffers have changed.");
                } else if (res == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat encformat = encoder.getOutputFormat();

                    Log.d("MainActivity", "encoder output format has changed to " + encformat);
                }


                //Get index of codec (decoder) output buffer (raw data)
                if (decoder == null)
                    res = MediaCodec.INFO_TRY_AGAIN_LATER;
                else
                    res = decoder.dequeueOutputBuffer(info, kTimeOutUs);

                if (res >= 0) {
                  //  Log.d("MainActivity", "decoder output buf index " + outputBufIndex);
                    int outputBufIndex = res;
                    ByteBuffer buf = decoderOutputBuffers[outputBufIndex];

                    buf.position(info.offset);
                    buf.limit(info.offset + info.size);

                    if (info.size > 0) {
                        //errors = checkFrame(buf, info, oformat, width, height, threshold);
                    }


                    //Return the used Output buffer to the Codec (decoder)
                    decoder.releaseOutputBuffer(outputBufIndex, (surface != null) /* render */);

                    numOutputFrames++;
                    if ((numOutputFrames % (frameRate * 3)) == 0) {
                        Log.d("MainActivity", "numInputFrames " + numInputFrames + " numOutputFrames " + numOutputFrames + " actualFrameRate " + (float) (numOutputFrames - actualOutputFrame) / (float) (System.currentTimeMillis() - actualOutputFrameLap) * 1000.0f + " lapavg " + lapavg);
                        actualOutputFrame = numOutputFrames;
                        actualOutputFrameLap = System.currentTimeMillis();
                    }

                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.d("MainActivity", "saw output EOS.");
                        sawOutputEOS = true;
                    }
                } else if (res == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    decoderOutputBuffers = decoder.getOutputBuffers();

                    Log.d("MainActivity", "decoder output buffers have changed.");
                } else if (res == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    oformat = decoder.getOutputFormat();

                    Log.d("MainActivity", "decoder output format has changed to " + oformat);
                }

            }

            encoder.stop();
            encoder.release();
            decoder.stop();
            decoder.release();

            Log.d("MainActivity", "complete.");
        }
    }

}
