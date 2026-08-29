#include <jni.h>

#include <array>
#include <chrono>
#include <cstddef>
#include <limits>
#include <new>

#include "ground_filter.h"

namespace {

constexpr jsize kObstacleGridCellCount = 64 * 64;
constexpr jsize kMetricCount = 4;

struct GroundFilterContext {
    GroundFilterContext(
        float fit_roi_top_value,
        float classification_roi_top_value,
        float obstacle_max_depth_meters_value,
        float fit_max_depth_meters_value,
        int sample_step_value,
        int max_iterations_value
    )
        : fit_roi_top(fit_roi_top_value),
          classification_roi_top(classification_roi_top_value),
          obstacle_max_depth_meters(obstacle_max_depth_meters_value),
          fit_max_depth_meters(fit_max_depth_meters_value),
          sample_step(sample_step_value),
          max_iterations(max_iterations_value) {
        mle_config.fit_roi_top = fit_roi_top;
        mle_config.fit_max_depth = fit_max_depth_meters;
        mle_config.sample_step = sample_step;
        mle_config.max_iterations = max_iterations;
        classification_config.classification_roi_top = classification_roi_top;
        classification_config.obstacle_max_depth = obstacle_max_depth_meters;
    }

    float fit_roi_top;
    float classification_roi_top;
    float obstacle_max_depth_meters;
    float fit_max_depth_meters;
    int sample_step;
    int max_iterations;
    glasses::ground::MleGroundConfig mle_config;
    glasses::ground::GroundPlaneWorkspace fit_workspace;
    glasses::ground::GroundPlaneFitResult last_fit_result;
    glasses::ground::GroundClassificationConfig classification_config;
    glasses::ground::GroundClassificationWorkspace classification_workspace;
    glasses::ground::GroundClassificationResult last_classification_result;
    std::array<jfloat, kObstacleGridCellCount> zero_occupancy{};
};

void ThrowJavaException(JNIEnv* env, const char* class_name, const char* message) {
    jclass exception_class = env->FindClass(class_name);
    if (exception_class != nullptr) {
        env->ThrowNew(exception_class, message);
    }
}

GroundFilterContext* ContextFromHandle(JNIEnv* env, jlong handle) {
    if (handle == 0) {
        ThrowJavaException(env, "java/lang/IllegalStateException", "Native ground filter handle is null");
        return nullptr;
    }
    return reinterpret_cast<GroundFilterContext*>(handle);
}

bool RequireArrayLength(
    JNIEnv* env,
    jarray array,
    jsize expected,
    const char* message
) {
    if (array == nullptr || env->GetArrayLength(array) != expected) {
        ThrowJavaException(env, "java/lang/IllegalArgumentException", message);
        return false;
    }
    return true;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_glasses_ground_NativeGroundFilter_nativeCreate(
    JNIEnv* env,
    jobject,
    jfloat fit_roi_top,
    jfloat classification_roi_top,
    jfloat obstacle_max_depth_meters,
    jfloat fit_max_depth_meters,
    jint sample_step,
    jint max_iterations
) {
    auto* context = new (std::nothrow) GroundFilterContext(
        fit_roi_top,
        classification_roi_top,
        obstacle_max_depth_meters,
        fit_max_depth_meters,
        sample_step,
        max_iterations
    );
    if (context == nullptr) {
        ThrowJavaException(env, "java/lang/OutOfMemoryError", "Unable to allocate native ground filter context");
        return 0;
    }
    return reinterpret_cast<jlong>(context);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_glasses_ground_NativeGroundFilter_nativeProcess(
    JNIEnv* env,
    jobject,
    jlong handle,
    jfloatArray depth_values,
    jint width,
    jint height,
    jfloatArray obstacle_occupancy,
    jbyteArray class_map,
    jdoubleArray metrics
) {
    const auto start_time = std::chrono::steady_clock::now();
    GroundFilterContext* context = ContextFromHandle(env, handle);
    if (context == nullptr) return JNI_FALSE;
    if (width <= 0 || height <= 0) {
        ThrowJavaException(env, "java/lang/IllegalArgumentException", "Depth dimensions must be positive");
        return JNI_FALSE;
    }

    const jlong pixel_count = static_cast<jlong>(width) * static_cast<jlong>(height);
    if (pixel_count > static_cast<jlong>(std::numeric_limits<jsize>::max())) {
        ThrowJavaException(env, "java/lang/IllegalArgumentException", "Depth dimensions are too large");
        return JNI_FALSE;
    }
    if (!RequireArrayLength(
            env,
            depth_values,
            static_cast<jsize>(pixel_count),
            "Depth array size must match width and height"
        ) ||
        !RequireArrayLength(
            env,
            obstacle_occupancy,
            kObstacleGridCellCount,
            "Obstacle occupancy must contain exactly 4096 values"
        ) ||
        !RequireArrayLength(env, metrics, kMetricCount, "Native metrics must contain exactly 4 values")) {
        return JNI_FALSE;
    }
    if (class_map != nullptr && env->GetArrayLength(class_map) != pixel_count) {
        ThrowJavaException(env, "java/lang/IllegalArgumentException", "Class map size must match depth dimensions");
        return JNI_FALSE;
    }

    jboolean is_copy = JNI_FALSE;
    jfloat* depth = env->GetFloatArrayElements(depth_values, &is_copy);
    if (depth == nullptr) return JNI_FALSE;
    context->last_fit_result = context->fit_workspace.Fit(
        depth,
        width,
        height,
        context->mle_config
    );
    const glasses::ground::NormalizedCoordinateTable* coordinates =
        context->fit_workspace.coordinates();
    if (coordinates != nullptr) {
        context->last_classification_result = context->classification_workspace.Classify(
            depth,
            width,
            height,
            context->mle_config,
            context->classification_config,
            context->last_fit_result,
            *coordinates,
            class_map != nullptr
        );
    } else {
        context->last_classification_result = {};
    }
    env->ReleaseFloatArrayElements(depth_values, depth, JNI_ABORT);

    const auto& native_class_map = context->classification_workspace.class_map();
    if (class_map != nullptr && native_class_map.size() == static_cast<std::size_t>(pixel_count)) {
        env->SetByteArrayRegion(
            class_map,
            0,
            static_cast<jsize>(pixel_count),
            reinterpret_cast<const jbyte*>(native_class_map.data())
        );
    }
    const auto& native_occupancy = coordinates != nullptr
        ? context->classification_workspace.obstacle_occupancy()
        : context->zero_occupancy;
    env->SetFloatArrayRegion(
        obstacle_occupancy,
        0,
        kObstacleGridCellCount,
        native_occupancy.data()
    );
    const double processing_ms = std::chrono::duration<double, std::milli>(
        std::chrono::steady_clock::now() - start_time).count();
    const std::array<jdouble, kMetricCount> native_metrics{
        context->last_classification_result.ground_fraction,
        context->last_classification_result.obstacle_fraction,
        context->last_classification_result.unknown_fraction,
        processing_ms,
    };
    env->SetDoubleArrayRegion(metrics, 0, kMetricCount, native_metrics.data());
    if (env->ExceptionCheck()) return JNI_FALSE;
    return context->last_fit_result.succeeded() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_glasses_ground_NativeGroundFilter_nativeReset(
    JNIEnv* env,
    jobject,
    jlong handle
) {
    GroundFilterContext* context = ContextFromHandle(env, handle);
    if (context == nullptr) return;
    context->fit_workspace.Reset();
    context->classification_workspace.Reset();
    context->last_fit_result = {};
    context->last_classification_result = {};
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_glasses_ground_NativeGroundFilter_nativeDestroy(
    JNIEnv*,
    jobject,
    jlong handle
) {
    delete reinterpret_cast<GroundFilterContext*>(handle);
}
