/*
 * Copyright (C) 2007 The Android Open Source Project
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

#ifndef ANDROID_FRAMEBUFFER_NATIVE_WINDOW_H
#define ANDROID_FRAMEBUFFER_NATIVE_WINDOW_H

#include <stdint.h>
#include <sys/types.h>

#include <EGL/egl.h>

#include <utils/threads.h>
#include <ui/Rect.h>

#include <pixelflinger/pixelflinger.h>

#include <ui/egl/android_natives.h>

#define NUM_FRAMEBUFFERS_MAX 3

extern "C" EGLNativeWindowType android_createDisplaySurface(void);

// ---------------------------------------------------------------------------
namespace android {
// ---------------------------------------------------------------------------

class Surface;
class NativeBuffer;

// ---------------------------------------------------------------------------

class FramebufferNativeWindow 
    : public EGLNativeBase<
        ANativeWindow, 
        FramebufferNativeWindow, 
        LightRefBase<FramebufferNativeWindow> >
{
public:
    FramebufferNativeWindow(); 

    framebuffer_device_t const * getDevice() const { return fbDev; } 
    void orientationChanged(int orientation) {
                                if (fbDev->orientationChanged)
                                    fbDev->orientationChanged(fbDev, orientation);
                              }
    void videoOverlayStarted(bool started)
                              {
                                if (fbDev->videoOverlayStarted)
                                    fbDev->videoOverlayStarted(fbDev, started);
                              }
    void enableHDMIOutput(int enable)
                              {
                                if (fbDev->enableHDMIOutput)
                                    fbDev->enableHDMIOutput(fbDev, enable);
                              }
    void setActionSafeWidthRatio(float asWidthRatio)
                              {
                                if (fbDev->setActionSafeWidthRatio)
                                    fbDev->setActionSafeWidthRatio(fbDev, asWidthRatio);
                              }
    void setActionSafeHeightRatio(float asHeightRatio)
                              {
                                if (fbDev->setActionSafeHeightRatio)
                                    fbDev->setActionSafeHeightRatio(fbDev, asHeightRatio);
                              }
    bool isUpdateOnDemand() const { return mUpdateOnDemand; }
    status_t setUpdateRectangle(const Rect& updateRect);
    status_t compositionComplete();
    buffer_handle_t getCurrentBufferHandle(ANativeWindow* window);
    
    // for debugging only
    int getCurrentBufferIndex() const;

private:
    friend class LightRefBase<FramebufferNativeWindow>;    
    ~FramebufferNativeWindow(); // this class cannot be overloaded
    static int setSwapInterval(ANativeWindow* window, int interval);
    static int dequeueBuffer(ANativeWindow* window, android_native_buffer_t** buffer);
    static int lockBuffer(ANativeWindow* window, android_native_buffer_t* buffer);
    static int queueBuffer(ANativeWindow* window, android_native_buffer_t* buffer);
    static int query(ANativeWindow* window, int what, int* value);
    static int perform(ANativeWindow* window, int operation, ...);
    
    framebuffer_device_t* fbDev;
    alloc_device_t* grDev;

    sp<NativeBuffer> buffers[NUM_FRAMEBUFFERS_MAX];
    sp<NativeBuffer> front;
    
    mutable Mutex mutex;
    Condition mCondition;
    int32_t mNumBuffers;
    int32_t mNumFreeBuffers;
    int32_t mBufferHead;
    int32_t mCurrentBufferIndex;
    bool mUpdateOnDemand;
};
    
// ---------------------------------------------------------------------------
}; // namespace android
// ---------------------------------------------------------------------------

#endif // ANDROID_FRAMEBUFFER_NATIVE_WINDOW_H

