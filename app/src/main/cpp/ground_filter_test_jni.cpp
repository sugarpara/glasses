#include <jni.h>

#include <array>
#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <vector>

#include "ground_filter.h"

namespace {

void ThrowJavaException(JNIEnv* env, const char* class_name, const char* message) {
    jclass exception_class = env->FindClass(class_name);
    if (exception_class != nullptr) env->ThrowNew(exception_class, message);
}

bool RequireSameLength(JNIEnv* env, std::initializer_list<jarray> arrays) {
    jsize expected = -1;
    for (jarray array : arrays) {
        if (array == nullptr) {
            ThrowJavaException(env, "java/lang/IllegalArgumentException", "Native math input cannot be null");
            return false;
        }
        const jsize length = env->GetArrayLength(array);
        if (expected < 0) expected = length;
        if (length != expected) {
            ThrowJavaException(env, "java/lang/IllegalArgumentException", "Native math inputs must have equal lengths");
            return false;
        }
    }
    return true;
}

jdoubleArray NewDoubleArray(JNIEnv* env, const double* values, jsize count) {
    jdoubleArray result = env->NewDoubleArray(count);
    if (result != nullptr && count > 0) env->SetDoubleArrayRegion(result, 0, count, values);
    return result;
}

}  // namespace

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_example_glasses_ground_GroundFilterMathTest_nativeInverseDepth(
    JNIEnv* env,
    jobject,
    jfloatArray depth
) {
    if (depth == nullptr) return nullptr;
    const jsize count = env->GetArrayLength(depth);
    std::vector<jfloat> input(static_cast<std::size_t>(count));
    std::vector<jdouble> output(static_cast<std::size_t>(count));
    env->GetFloatArrayRegion(depth, 0, count, input.data());
    for (jsize index = 0; index < count; ++index) {
        output[static_cast<std::size_t>(index)] = glasses::ground::InverseDepthOrNaN(input[index]);
    }
    return NewDoubleArray(env, output.data(), count);
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_example_glasses_ground_GroundFilterMathTest_nativeNormalizedCoordinates(
    JNIEnv* env,
    jobject,
    jint width,
    jint height
) {
    if (width <= 0 || height <= 0) {
        ThrowJavaException(env, "java/lang/IllegalArgumentException", "Dimensions must be positive");
        return nullptr;
    }
    const glasses::ground::NormalizedCoordinateTable table(width, height);
    const std::size_t count = table.x().size();
    std::vector<double> result(count * 2);
    std::copy(table.x().begin(), table.x().end(), result.begin());
    std::copy(table.y().begin(), table.y().end(), result.begin() + static_cast<std::ptrdiff_t>(count));
    return NewDoubleArray(env, result.data(), static_cast<jsize>(result.size()));
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_example_glasses_ground_GroundFilterMathTest_nativeSolveThreeByThree(
    JNIEnv* env,
    jobject,
    jdoubleArray matrix,
    jdoubleArray right_hand_side
) {
    if (matrix == nullptr || right_hand_side == nullptr || env->GetArrayLength(matrix) != 9 ||
        env->GetArrayLength(right_hand_side) != 3) {
        ThrowJavaException(env, "java/lang/IllegalArgumentException", "Expected a 3x3 matrix and 3-element vector");
        return nullptr;
    }
    std::array<double, 9> native_matrix{};
    std::array<double, 3> native_rhs{};
    std::array<double, 3> solution{};
    env->GetDoubleArrayRegion(matrix, 0, 9, native_matrix.data());
    env->GetDoubleArrayRegion(right_hand_side, 0, 3, native_rhs.data());
    if (!glasses::ground::SolveThreeByThree(native_matrix, native_rhs, &solution)) {
        ThrowJavaException(env, "java/lang/IllegalArgumentException", "Matrix is singular");
        return nullptr;
    }
    return NewDoubleArray(env, solution.data(), 3);
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_example_glasses_ground_GroundFilterMathTest_nativeWeightedLeastSquares(
    JNIEnv* env,
    jobject,
    jdoubleArray x,
    jdoubleArray y,
    jdoubleArray response,
    jdoubleArray weights
) {
    if (!RequireSameLength(env, {x, y, response, weights})) return nullptr;
    const jsize count = env->GetArrayLength(x);
    std::vector<double> native_x(static_cast<std::size_t>(count));
    std::vector<double> native_y(static_cast<std::size_t>(count));
    std::vector<double> native_response(static_cast<std::size_t>(count));
    std::vector<double> native_weights(static_cast<std::size_t>(count));
    env->GetDoubleArrayRegion(x, 0, count, native_x.data());
    env->GetDoubleArrayRegion(y, 0, count, native_y.data());
    env->GetDoubleArrayRegion(response, 0, count, native_response.data());
    env->GetDoubleArrayRegion(weights, 0, count, native_weights.data());
    glasses::ground::PlaneCoefficients coefficients;
    if (!glasses::ground::WeightedLeastSquares(
            native_x.data(), native_y.data(), native_response.data(), native_weights.data(),
            static_cast<std::size_t>(count), &coefficients)) {
        ThrowJavaException(env, "java/lang/IllegalArgumentException", "Least-squares system is singular");
        return nullptr;
    }
    const std::array<double, 3> result{coefficients.a, coefficients.b, coefficients.c};
    return NewDoubleArray(env, result.data(), 3);
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_example_glasses_ground_GroundFilterMathTest_nativeFitRansac(
    JNIEnv* env,
    jobject,
    jdoubleArray x,
    jdoubleArray y,
    jdoubleArray response,
    jint iterations,
    jdouble residual_threshold,
    jdouble fallback_inlier_quantile
) {
    if (!RequireSameLength(env, {x, y, response})) return nullptr;
    const jsize count = env->GetArrayLength(x);
    std::vector<double> native_x(static_cast<std::size_t>(count));
    std::vector<double> native_y(static_cast<std::size_t>(count));
    std::vector<double> native_response(static_cast<std::size_t>(count));
    env->GetDoubleArrayRegion(x, 0, count, native_x.data());
    env->GetDoubleArrayRegion(y, 0, count, native_y.data());
    env->GetDoubleArrayRegion(response, 0, count, native_response.data());
    glasses::ground::RansacWorkspace workspace;
    glasses::ground::PlaneCoefficients coefficients;
    std::size_t inlier_count = 0;
    if (!glasses::ground::FitPlaneRansac(
            native_x.data(), native_y.data(), native_response.data(),
            static_cast<std::size_t>(count), iterations, residual_threshold,
            fallback_inlier_quantile, &workspace, &coefficients, &inlier_count)) {
        ThrowJavaException(env, "java/lang/IllegalArgumentException", "RANSAC plane fit failed");
        return nullptr;
    }
    const std::array<double, 4> result{
        coefficients.a, coefficients.b, coefficients.c, static_cast<double>(inlier_count),
    };
    return NewDoubleArray(env, result.data(), 4);
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_example_glasses_ground_GroundFilterMathTest_nativeEstimateMadSigma(
    JNIEnv* env,
    jobject,
    jdoubleArray residuals,
    jbooleanArray inliers,
    jdouble min_sigma,
    jdouble max_sigma
) {
    if (!RequireSameLength(env, {residuals, inliers})) return 0.0;
    const jsize count = env->GetArrayLength(residuals);
    std::vector<double> native_residuals(static_cast<std::size_t>(count));
    std::vector<jboolean> native_inliers(static_cast<std::size_t>(count));
    std::vector<std::uint8_t> mask(static_cast<std::size_t>(count));
    std::vector<double> scratch;
    env->GetDoubleArrayRegion(residuals, 0, count, native_residuals.data());
    env->GetBooleanArrayRegion(inliers, 0, count, native_inliers.data());
    for (jsize index = 0; index < count; ++index) {
        mask[static_cast<std::size_t>(index)] = native_inliers[static_cast<std::size_t>(index)] ? 1U : 0U;
    }
    return glasses::ground::EstimateMadSigma(
        native_residuals.data(), mask.data(), static_cast<std::size_t>(count), min_sigma,
        max_sigma, &scratch);
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_example_glasses_ground_GroundPlaneFitTest_nativeGroundPosterior(
    JNIEnv* env,
    jobject,
    jdoubleArray residuals,
    jdouble sigma,
    jdouble ground_prior,
    jdouble outlier_density
) {
    if (residuals == nullptr) return nullptr;
    const jsize count = env->GetArrayLength(residuals);
    std::vector<double> native_residuals(static_cast<std::size_t>(count));
    std::vector<double> posterior(static_cast<std::size_t>(count));
    env->GetDoubleArrayRegion(residuals, 0, count, native_residuals.data());
    for (jsize index = 0; index < count; ++index) {
        posterior[static_cast<std::size_t>(index)] = glasses::ground::GroundPosterior(
            native_residuals[static_cast<std::size_t>(index)],
            sigma,
            ground_prior,
            outlier_density);
    }
    return NewDoubleArray(env, posterior.data(), count);
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_example_glasses_ground_GroundPlaneFitTest_nativeFitGroundPlane(
    JNIEnv* env,
    jobject,
    jfloatArray depth,
    jint width,
    jint height,
    jdoubleArray config_values
) {
    constexpr jsize kConfigValueCount = 19;
    constexpr jsize kResultValueCount = 11;
    if (depth == nullptr || config_values == nullptr || width <= 0 || height <= 0 ||
        env->GetArrayLength(config_values) != kConfigValueCount ||
        static_cast<jlong>(env->GetArrayLength(depth)) !=
            static_cast<jlong>(width) * static_cast<jlong>(height)) {
        ThrowJavaException(env, "java/lang/IllegalArgumentException", "Invalid ground-plane fit input");
        return nullptr;
    }

    std::array<double, kConfigValueCount> values{};
    env->GetDoubleArrayRegion(config_values, 0, kConfigValueCount, values.data());
    glasses::ground::MleGroundConfig config;
    config.fit_roi_top = values[0];
    config.sample_step = static_cast<int>(values[1]);
    config.max_iterations = static_cast<int>(values[2]);
    config.convergence_tolerance = values[3];
    config.min_sigma = values[4];
    config.max_sigma = values[5];
    config.max_accepted_sigma = values[6];
    config.initial_inlier_quantile = values[7];
    config.ransac_iterations = static_cast<int>(values[8]);
    config.ransac_residual_threshold = values[9];
    config.sigma_multiplier = values[10];
    config.min_ground_support = values[11];
    config.min_bottom_support = values[12];
    config.discontinuity_threshold = values[13];
    config.discontinuity_max_threshold = values[14];
    config.max_discontinuity_support = values[15];
    config.min_candidate_count = static_cast<std::size_t>(std::max(values[16], 0.0));
    config.min_depth = values[17];
    config.fit_max_depth = values[18];

    const jsize depth_count = env->GetArrayLength(depth);
    std::vector<float> native_depth(static_cast<std::size_t>(depth_count));
    env->GetFloatArrayRegion(depth, 0, depth_count, native_depth.data());
    glasses::ground::GroundPlaneWorkspace workspace;
    const glasses::ground::GroundPlaneFitResult fit =
        workspace.Fit(native_depth.data(), width, height, config);
    const std::array<double, kResultValueCount> result{
        static_cast<double>(fit.status),
        fit.model.coefficients.a,
        fit.model.coefficients.b,
        fit.model.coefficients.c,
        fit.model.sigma,
        fit.model.ground_prior,
        fit.model.outlier_density,
        static_cast<double>(fit.model.iterations),
        static_cast<double>(fit.model.candidate_count),
        fit.ground_support,
        fit.bottom_support,
    };
    return NewDoubleArray(env, result.data(), kResultValueCount);
}
