package com.example.minimal_precision_data_collection;

import android.os.Build;
import android.view.View;

import androidx.annotation.RequiresApi;

public class CustomClickListener implements android.view.View.OnClickListener
{
    CaptureVideoFragment mCaptureVideoFragment;

    CustomClickListener(CaptureVideoFragment captureVideoFragment)
    {
        mCaptureVideoFragment = captureVideoFragment;
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    public void onClick(View view)
    {
        if (view.getId() == R.id.video_record) {
            if (mCaptureVideoFragment.mIsRecordingVideo) {
                mCaptureVideoFragment.stopRecordingVideo();
                mCaptureVideoFragment.startPreview();
            } else {
                mCaptureVideoFragment.startRecordingVideo();
            }
        }
    }
}
