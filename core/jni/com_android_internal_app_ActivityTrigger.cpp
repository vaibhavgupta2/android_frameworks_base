/*
 * Copyright (c) 2011, Code Aurora Forum. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *    * Redistributions of source code must retain the above copyright
 *      notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above copyright
 *      notice, this list of conditions and the following disclaimer in the
 *      documentation and/or other materials provided with the distribution.
 *    * Neither the name of Code Aurora nor
 *      the names of its contributors may be used to endorse or promote
 *      products derived from this software without specific prior written
 *      permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NON-INFRINGEMENT ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

#include "jni.h"
#include "JNIHelp.h"
#include <android_runtime/AndroidRuntime.h>

#include <dlfcn.h>
#include <limits.h>

#define LOG_TAG "ActTriggerJNI"
#include <utils/Log.h>

#define ACTIVIT_TRIGGER_LIBRARY		"/system/lib/libacttrig.so"

namespace android
{

// ----------------------------------------------------------------------------

static void (*startActivity)(const char *)  = NULL;
static void (*resumeActivity)(const char *) = NULL;

// ----------------------------------------------------------------------------

static void
com_android_internal_app_ActivityTrigger_native_at_init()
{
    void *handle;
    const char *rc;
    void (*init)(void);

    handle = dlopen(ACTIVIT_TRIGGER_LIBRARY, RTLD_NOW | RTLD_LOCAL);
    if (handle == NULL) {
        return;
    }

    dlerror();

    *(void **) (&startActivity) = dlsym(handle, "activity_trigger_start");
    if ((rc = dlerror()) != NULL) {
        goto cleanup;
    }
    *(void **) (&resumeActivity) = dlsym(handle, "activity_trigger_resume");
    if ((rc = dlerror()) != NULL) {
        goto cleanup;
    }
    *(void **) (&init) = dlsym(handle, "activity_trigger_init");
    if ((rc = dlerror()) != NULL) {
        goto cleanup;
    }
    (*init)();
    return;

cleanup:
    startActivity  = NULL;
    resumeActivity = NULL;
}

static void
com_android_internal_app_ActivityTrigger_native_at_startActivity(JNIEnv *env, jobject clazz, jstring activity)
{
    if (startActivity && activity) {
        const char *actStr = env->GetStringUTFChars(activity, NULL);
        if (actStr) {
            (*startActivity)(actStr);
        }
    }
}

static void
com_android_internal_app_ActivityTrigger_native_at_resumeActivity(JNIEnv *env, jobject clazz, jstring activity)
{
    if (resumeActivity && activity) {
        const char *actStr = env->GetStringUTFChars(activity, NULL);
        if (actStr) {
            (*resumeActivity)(actStr);
        }
    }
}

// ----------------------------------------------------------------------------

static JNINativeMethod gMethods[] = {
    {"native_at_startActivity",  "(Ljava/lang/String;)V", (void *)com_android_internal_app_ActivityTrigger_native_at_startActivity},
    {"native_at_resumeActivity", "(Ljava/lang/String;)V", (void *)com_android_internal_app_ActivityTrigger_native_at_resumeActivity},
};


int register_com_android_internal_app_ActivityTrigger(JNIEnv *env)
{
    com_android_internal_app_ActivityTrigger_native_at_init();

    return AndroidRuntime::registerNativeMethods(env,
            "com/android/internal/app/ActivityTrigger", gMethods, NELEM(gMethods));
}

}   // namespace android
