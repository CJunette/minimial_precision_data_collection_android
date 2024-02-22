package com.example.minimal_precision_data_collection;

import android.animation.Animator;
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.graphics.Color;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.LinearInterpolator;

import java.io.File;

public class CustomOnTouchListener implements android.view.View.OnTouchListener
{
    public android.view.GestureDetector mGestureDetector;
    public CaptureVideoFragment mCaptureVideoFragment;
    public static final Integer DURATION = 1000;
    public String mLastFileName = "";

    public CustomOnTouchListener(android.content.Context context, CaptureVideoFragment captureVideoFragment)
    {
        mGestureDetector = new android.view.GestureDetector(context, new MyCustomGestureListener());
        mCaptureVideoFragment = captureVideoFragment;
    }

    public static class ColorTransition {
        public interface OnColorTransitionListener {
            void onTransitionEnd();
        }

        public static void startColorTransition(View targetView, int startColor, int endColor, long duration, OnColorTransitionListener listener)
        {
            // 创建颜色渐变动画
            ObjectAnimator colorAnimator = ObjectAnimator.ofInt(targetView, "backgroundColor", startColor, endColor);

            // 设置动画持续时间
            colorAnimator.setDuration(duration);

            // 设置插值器，这里使用线性插值器
            colorAnimator.setInterpolator(new LinearInterpolator());

            // 设置颜色过渡的过程中使用ArgbEvaluator进行插值
            colorAnimator.setEvaluator(new ArgbEvaluator());

            // 添加动画结束监听器
            colorAnimator.addListener(new ValueAnimator.AnimatorListener() {
                @Override
                public void onAnimationStart(Animator animator) {
                    // Animation start
                }

                @Override
                public void onAnimationEnd(Animator animator) {
                    // Animation end
                    if (listener != null) {
                        listener.onTransitionEnd();
                    }
                }

                @Override
                public void onAnimationCancel(Animator animator) {
                    // Animation cancel
                }

                @Override
                public void onAnimationRepeat(Animator animator) {
                    // Animation repeat
                }
            });

            // 启动动画
            colorAnimator.start();
        }
    }

    @Override
    public boolean onTouch(View view, MotionEvent event)
    {
        if (view.getId() == R.id.view_overlay_button || view.getId() == R.id.video_record)
        {
            mGestureDetector.onTouchEvent(event);
        }
        return true;
    }

    public class MyCustomGestureListener extends android.view.GestureDetector.SimpleOnGestureListener
    {

        public boolean onSingleTapUp(MotionEvent e)
        {
            Log.e("GESTURE_DETECTOR", "onSingleTapUp");
            return false;
        }

        public void onLongPress(MotionEvent e)
        {
            Log.e("GESTURE_DETECTOR", "onLongPress");
        }

        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY)
        {
            Log.e("GESTURE_DETECTOR", "onScroll");
            if (distanceX > 5 && distanceY > 10 && mLastFileName.length() > 0)
            {
                // TODO 添加一个scroll能够返回上一个点的功能。
                // delete the video of name mLastFileName
                File mFileToDelete = new File(mLastFileName);
                if (mFileToDelete.exists())
                {
                    if (mFileToDelete.delete())
                    {
                        Log.d("File Deletion", "File deleted successfully");
                    } else
                    {
                        Log.e("File Deletion", "Failed to delete file");
                    }
                } else
                {
                    Log.e("File Deletion", "File does not exist");
                }

                mLastFileName = "";

                View currentGazeView = mCaptureVideoFragment.mViewsForGaze.get(mCaptureVideoFragment.mIndicesForGaze.get(mCaptureVideoFragment.mGazePointIndex)).mView;
                currentGazeView.setVisibility(View.INVISIBLE);
                currentGazeView.invalidate();

                mCaptureVideoFragment.mGazePointIndex -= 1;
                currentGazeView = mCaptureVideoFragment.mViewsForGaze.get(mCaptureVideoFragment.mIndicesForGaze.get(mCaptureVideoFragment.mGazePointIndex)).mView;
                currentGazeView.setVisibility(View.VISIBLE);

            }

            return false;
        }

        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY)
        {
            return false;
        }

        public void onShowPress(MotionEvent e)
        {
        }

        public boolean onDown(MotionEvent e)
        {
            return false;
        }

        public boolean onDoubleTap(MotionEvent e)
        {
            Log.e("GESTURE_DETECTOR", "onDoubleTap");

            if (!mCaptureVideoFragment.mIsRecordingVideo)
            {
                Log.e("GESTURE_DETECTOR_DOUBLE_TAP", mCaptureVideoFragment.mViewsForGaze.get(mCaptureVideoFragment.mIndicesForGaze.get(mCaptureVideoFragment.mGazePointIndex)).mName);
                View currentGazeView = mCaptureVideoFragment.mViewsForGaze.get(mCaptureVideoFragment.mIndicesForGaze.get(mCaptureVideoFragment.mGazePointIndex)).mView;
                ColorTransition.startColorTransition(currentGazeView, Color.BLACK, Color.RED, DURATION, new ColorTransition.OnColorTransitionListener() {
                    @Override
                    public void onTransitionEnd() {
                        mLastFileName = mCaptureVideoFragment.mNextVideoFilePath;
                        mCaptureVideoFragment.stopRecordingVideo();

                        currentGazeView.setBackgroundColor(Color.BLACK);
                        currentGazeView.setVisibility(View.INVISIBLE);
                        currentGazeView.invalidate();
                        mCaptureVideoFragment.mGazePointIndex += 1;
                        if (mCaptureVideoFragment.mGazePointIndex == mCaptureVideoFragment.mIndicesForGaze.size())
                        {
                            android.widget.Toast.makeText(mCaptureVideoFragment.getContext(), "All gaze points have been recorded", android.widget.Toast.LENGTH_SHORT).show();
                            mCaptureVideoFragment.mGazePointIndex = 0;
                        }

                        View nextGazeView = mCaptureVideoFragment.mViewsForGaze.get(mCaptureVideoFragment.mIndicesForGaze.get(mCaptureVideoFragment.mGazePointIndex)).mView;
                        nextGazeView.setVisibility(View.VISIBLE);

                        mCaptureVideoFragment.mNextVideoFilePath = mCaptureVideoFragment.getVideoFile();
                        mCaptureVideoFragment.startPreview();
                    }
                });

                mCaptureVideoFragment.startRecordingVideo();
                // TODO 目前只写了start recording，更重要的是record同时点的颜色变化，及record的持续时间
            }
            return false;
        }

        public boolean onDoubleTapEvent(MotionEvent e)
        {
            return false;
        }

        public boolean onSingleTapConfirmed(MotionEvent e)
        {
            return false;
        }

        public boolean onContextClick(MotionEvent e)
        {
            return false;
        }
    }
}
