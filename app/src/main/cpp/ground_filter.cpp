#include "ground_filter.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <stdexcept>

namespace glasses::ground {
namespace {

constexpr double kMinimumWeight = 1e-8;
constexpr double kPivotTolerance = 1e-12;

class DeterministicGenerator {
public:
    explicit DeterministicGenerator(std::uint64_t seed) : state_(seed) {}

    std::size_t Bounded(std::size_t bound) {
        if (bound == 0) return 0;
        const std::uint64_t range = static_cast<std::uint64_t>(bound);
        const std::uint64_t limit = std::numeric_limits<std::uint64_t>::max() -
            (std::numeric_limits<std::uint64_t>::max() % range);
        std::uint64_t value;
        do {
            value = Next();
        } while (value >= limit);
        return static_cast<std::size_t>(value % range);
    }

private:
    std::uint64_t Next() {
        state_ += 0x9e3779b97f4a7c15ULL;
        std::uint64_t value = state_;
        value = (value ^ (value >> 30U)) * 0xbf58476d1ce4e5b9ULL;
        value = (value ^ (value >> 27U)) * 0x94d049bb133111ebULL;
        return value ^ (value >> 31U);
    }

    std::uint64_t state_;
};

bool AccumulateLeastSquares(
    const double* x,
    const double* y,
    const double* response,
    const double* weights,
    const std::uint8_t* included,
    std::size_t count,
    PlaneCoefficients* coefficients
) {
    if (x == nullptr || y == nullptr || response == nullptr || coefficients == nullptr) {
        return false;
    }

    double sum_w = 0.0;
    double sum_wx = 0.0;
    double sum_wy = 0.0;
    double sum_wxx = 0.0;
    double sum_wxy = 0.0;
    double sum_wyy = 0.0;
    double sum_wz = 0.0;
    double sum_wxz = 0.0;
    double sum_wyz = 0.0;
    std::size_t used = 0;

    for (std::size_t index = 0; index < count; ++index) {
        if (included != nullptr && included[index] == 0) continue;
        const double weight = weights == nullptr ? 1.0 : std::max(weights[index], kMinimumWeight);
        if (!std::isfinite(x[index]) || !std::isfinite(y[index]) ||
            !std::isfinite(response[index]) || !std::isfinite(weight)) {
            continue;
        }
        sum_w += weight;
        sum_wx += weight * x[index];
        sum_wy += weight * y[index];
        sum_wxx += weight * x[index] * x[index];
        sum_wxy += weight * x[index] * y[index];
        sum_wyy += weight * y[index] * y[index];
        sum_wz += weight * response[index];
        sum_wxz += weight * x[index] * response[index];
        sum_wyz += weight * y[index] * response[index];
        ++used;
    }

    if (used < 3) return false;
    const std::array<double, 9> matrix{
        sum_wxx, sum_wxy, sum_wx,
        sum_wxy, sum_wyy, sum_wy,
        sum_wx, sum_wy, sum_w,
    };
    const std::array<double, 3> rhs{sum_wxz, sum_wyz, sum_wz};
    std::array<double, 3> solution{};
    if (!SolveThreeByThree(matrix, rhs, &solution)) return false;
    *coefficients = {solution[0], solution[1], solution[2]};
    return std::isfinite(coefficients->a) && std::isfinite(coefficients->b) &&
        std::isfinite(coefficients->c);
}

double AbsoluteResidual(
    const PlaneCoefficients& coefficients,
    double x,
    double y,
    double response
) {
    return std::abs(response - (coefficients.a * x + coefficients.b * y + coefficients.c));
}

std::size_t FillInliers(
    const double* x,
    const double* y,
    const double* response,
    std::size_t count,
    const PlaneCoefficients& coefficients,
    double threshold,
    std::vector<double>* residuals,
    std::vector<std::uint8_t>* inliers
) {
    std::size_t inlier_count = 0;
    for (std::size_t index = 0; index < count; ++index) {
        const double residual = AbsoluteResidual(coefficients, x[index], y[index], response[index]);
        (*residuals)[index] = residual;
        const bool is_inlier = std::isfinite(residual) && residual <= threshold;
        (*inliers)[index] = is_inlier ? 1U : 0U;
        inlier_count += is_inlier ? 1U : 0U;
    }
    return inlier_count;
}

double Quantile(std::vector<double>* values, std::size_t count, double quantile) {
    if (count == 0) return std::numeric_limits<double>::quiet_NaN();
    std::sort(values->begin(), values->begin() + static_cast<std::ptrdiff_t>(count));
    const double position = std::clamp(quantile, 0.0, 1.0) * static_cast<double>(count - 1);
    const auto lower = static_cast<std::size_t>(std::floor(position));
    const auto upper = static_cast<std::size_t>(std::ceil(position));
    const double fraction = position - static_cast<double>(lower);
    return (*values)[lower] * (1.0 - fraction) + (*values)[upper] * fraction;
}

}  // namespace

bool RansacWorkspace::FitTrimmedPlane(
    const double* x,
    const double* y,
    const double* response,
    std::size_t count,
    double inlier_quantile,
    PlaneCoefficients* coefficients,
    std::size_t* final_inlier_count
) {
    if (!AccumulateLeastSquares(x, y, response, nullptr, nullptr, count, coefficients)) {
        return false;
    }
    std::fill(inliers_.begin(), inliers_.end(), 1U);
    *final_inlier_count = count;

    for (int iteration = 0; iteration < 6; ++iteration) {
        for (std::size_t index = 0; index < count; ++index) {
            const double residual = AbsoluteResidual(*coefficients, x[index], y[index], response[index]);
            residuals_[index] = residual;
            sorted_residuals_[index] = residual;
        }
        const double cutoff = Quantile(&sorted_residuals_, count, inlier_quantile);
        const double threshold = std::max(cutoff, kMinimumWeight);
        std::size_t next_count = 0;
        for (std::size_t index = 0; index < count; ++index) {
            const bool included = residuals_[index] <= threshold;
            next_inliers_[index] = included ? 1U : 0U;
            next_count += included ? 1U : 0U;
        }
        if (next_count < 3) break;

        PlaneCoefficients next_coefficients;
        if (!AccumulateLeastSquares(
                x,
                y,
                response,
                nullptr,
                next_inliers_.data(),
                count,
                &next_coefficients
            )) {
            break;
        }
        const bool unchanged = next_inliers_ == inliers_;
        *coefficients = next_coefficients;
        inliers_.swap(next_inliers_);
        *final_inlier_count = next_count;
        if (unchanged) break;
    }
    return true;
}

bool IsFinitePositiveDepth(float depth) {
    return std::isfinite(depth) && depth > 0.0F;
}

double InverseDepthOrNaN(float depth) {
    if (!IsFinitePositiveDepth(depth)) return std::numeric_limits<double>::quiet_NaN();
    return 1.0 / static_cast<double>(depth);
}

NormalizedCoordinateTable::NormalizedCoordinateTable(int width, int height)
    : width_(width), height_(height) {
    if (width <= 0 || height <= 0) {
        throw std::invalid_argument("Normalized coordinate dimensions must be positive");
    }
    const std::size_t count = static_cast<std::size_t>(width) * static_cast<std::size_t>(height);
    x_.resize(count);
    y_.resize(count);
    const double x_denominator = static_cast<double>(std::max(width - 1, 1));
    const double y_denominator = static_cast<double>(std::max(height - 1, 1));
    for (int row = 0; row < height; ++row) {
        for (int column = 0; column < width; ++column) {
            const std::size_t index = static_cast<std::size_t>(row) * width + column;
            x_[index] = 2.0 * static_cast<double>(column) / x_denominator - 1.0;
            y_[index] = static_cast<double>(row) / y_denominator;
        }
    }
}

bool SolveThreeByThree(
    const std::array<double, 9>& matrix,
    const std::array<double, 3>& right_hand_side,
    std::array<double, 3>* solution
) {
    if (solution == nullptr) return false;
    std::array<std::array<double, 4>, 3> augmented{};
    for (std::size_t row = 0; row < 3; ++row) {
        for (std::size_t column = 0; column < 3; ++column) {
            augmented[row][column] = matrix[row * 3 + column];
        }
        augmented[row][3] = right_hand_side[row];
    }

    for (std::size_t pivot = 0; pivot < 3; ++pivot) {
        std::size_t pivot_row = pivot;
        for (std::size_t row = pivot + 1; row < 3; ++row) {
            if (std::abs(augmented[row][pivot]) > std::abs(augmented[pivot_row][pivot])) {
                pivot_row = row;
            }
        }
        if (!std::isfinite(augmented[pivot_row][pivot]) ||
            std::abs(augmented[pivot_row][pivot]) <= kPivotTolerance) {
            return false;
        }
        if (pivot_row != pivot) std::swap(augmented[pivot], augmented[pivot_row]);

        const double divisor = augmented[pivot][pivot];
        for (std::size_t column = pivot; column < 4; ++column) {
            augmented[pivot][column] /= divisor;
        }
        for (std::size_t row = 0; row < 3; ++row) {
            if (row == pivot) continue;
            const double factor = augmented[row][pivot];
            for (std::size_t column = pivot; column < 4; ++column) {
                augmented[row][column] -= factor * augmented[pivot][column];
            }
        }
    }
    *solution = {augmented[0][3], augmented[1][3], augmented[2][3]};
    return std::isfinite((*solution)[0]) && std::isfinite((*solution)[1]) &&
        std::isfinite((*solution)[2]);
}

bool WeightedLeastSquares(
    const double* x,
    const double* y,
    const double* response,
    const double* weights,
    std::size_t count,
    PlaneCoefficients* coefficients
) {
    return AccumulateLeastSquares(x, y, response, weights, nullptr, count, coefficients);
}

void RansacWorkspace::Resize(std::size_t count) {
    residuals_.resize(count);
    sorted_residuals_.resize(count);
    inliers_.resize(count);
    next_inliers_.resize(count);
}

bool FitPlaneRansac(
    const double* x,
    const double* y,
    const double* response,
    std::size_t count,
    int iterations,
    double residual_threshold,
    double fallback_inlier_quantile,
    RansacWorkspace* workspace,
    PlaneCoefficients* coefficients,
    std::size_t* inlier_count
) {
    if (x == nullptr || y == nullptr || response == nullptr || workspace == nullptr ||
        coefficients == nullptr || inlier_count == nullptr || count < 3 || iterations <= 0 ||
        !std::isfinite(residual_threshold) || residual_threshold <= 0.0 ||
        !std::isfinite(fallback_inlier_quantile) || fallback_inlier_quantile < 0.0 ||
        fallback_inlier_quantile > 1.0) {
        return false;
    }
    workspace->Resize(count);
    DeterministicGenerator generator(0);
    PlaneCoefficients best_coefficients;
    double best_score = -1.0;
    bool found = false;

    for (int iteration = 0; iteration < iterations; ++iteration) {
        const std::size_t first = generator.Bounded(count);
        std::size_t second;
        do {
            second = generator.Bounded(count);
        } while (second == first);
        std::size_t third;
        do {
            third = generator.Bounded(count);
        } while (third == first || third == second);

        const std::array<double, 9> sample_matrix{
            x[first], y[first], 1.0,
            x[second], y[second], 1.0,
            x[third], y[third], 1.0,
        };
        const std::array<double, 3> sample_response{
            response[first], response[second], response[third],
        };
        std::array<double, 3> sample_solution{};
        if (!SolveThreeByThree(sample_matrix, sample_response, &sample_solution)) continue;
        const PlaneCoefficients candidate{sample_solution[0], sample_solution[1], sample_solution[2]};
        if (candidate.b <= 0.0) continue;

        std::size_t candidate_count = 0;
        std::size_t bottom_count = 0;
        for (std::size_t index = 0; index < count; ++index) {
            const double residual = AbsoluteResidual(candidate, x[index], y[index], response[index]);
            const bool is_inlier = std::isfinite(residual) && residual <= residual_threshold;
            workspace->next_inliers_[index] = is_inlier ? 1U : 0U;
            candidate_count += is_inlier ? 1U : 0U;
            bottom_count += is_inlier && y[index] >= 0.85 ? 1U : 0U;
        }
        if (candidate_count < 3) continue;
        const double score = static_cast<double>(candidate_count) + 0.75 * bottom_count;
        if (score > best_score) {
            best_score = score;
            best_coefficients = candidate;
            workspace->inliers_ = workspace->next_inliers_;
            *inlier_count = candidate_count;
            found = true;
        }
    }

    if (!found) {
        return workspace->FitTrimmedPlane(
            x,
            y,
            response,
            count,
            fallback_inlier_quantile,
            coefficients,
            inlier_count
        );
    }

    *coefficients = best_coefficients;
    for (int iteration = 0; iteration < 4; ++iteration) {
        PlaneCoefficients refined;
        if (!AccumulateLeastSquares(
                x,
                y,
                response,
                nullptr,
                workspace->inliers_.data(),
                count,
                &refined
            )) {
            break;
        }
        const std::size_t next_count = FillInliers(
            x,
            y,
            response,
            count,
            refined,
            residual_threshold,
            &workspace->residuals_,
            &workspace->next_inliers_
        );
        const bool unchanged = workspace->next_inliers_ == workspace->inliers_;
        *coefficients = refined;
        if (unchanged) break;
        if (next_count < 3) break;
        workspace->inliers_.swap(workspace->next_inliers_);
        *inlier_count = next_count;
    }
    return true;
}

double EstimateMadSigma(
    const double* residuals,
    const std::uint8_t* inliers,
    std::size_t count,
    double min_sigma,
    double max_sigma,
    std::vector<double>* scratch
) {
    if (residuals == nullptr || inliers == nullptr || scratch == nullptr || count == 0 ||
        !std::isfinite(min_sigma) || !std::isfinite(max_sigma) || min_sigma <= 0.0 ||
        max_sigma < min_sigma) {
        return std::numeric_limits<double>::quiet_NaN();
    }
    scratch->resize(count);
    std::size_t selected_count = 0;
    for (std::size_t index = 0; index < count; ++index) {
        if (inliers[index] == 0 || !std::isfinite(residuals[index])) continue;
        (*scratch)[selected_count++] = std::abs(residuals[index]);
    }
    if (selected_count == 0) return min_sigma;
    const double median = Quantile(scratch, selected_count, 0.5);
    return std::clamp(1.4826 * median, min_sigma, max_sigma);
}

double NormalDensity(double residual, double sigma) {
    if (!std::isfinite(residual) || !std::isfinite(sigma) || sigma <= 0.0) return 0.0;
    const double scaled = residual / sigma;
    const double exponent = std::clamp(-0.5 * scaled * scaled, -80.0, 0.0);
    constexpr double kSqrtTwoPi = 2.5066282746310005024;
    return std::exp(exponent) / (kSqrtTwoPi * sigma);
}

double GroundPosterior(
    double residual,
    double sigma,
    double ground_prior,
    double outlier_density
) {
    const double ground_density = NormalDensity(residual, sigma);
    const double numerator = ground_prior * ground_density;
    const double denominator = numerator + (1.0 - ground_prior) * outlier_density;
    return numerator / std::max(denominator, 1e-12);
}

namespace {

bool IsValidConfig(const MleGroundConfig& config) {
    return std::isfinite(config.fit_roi_top) && config.fit_roi_top >= 0.0 &&
        config.fit_roi_top < 1.0 && config.sample_step > 0 && config.max_iterations > 0 &&
        std::isfinite(config.convergence_tolerance) && config.convergence_tolerance > 0.0 &&
        std::isfinite(config.min_sigma) && std::isfinite(config.max_sigma) &&
        config.min_sigma > 0.0 && config.max_sigma >= config.min_sigma &&
        std::isfinite(config.max_accepted_sigma) &&
        config.max_accepted_sigma >= config.min_sigma &&
        config.max_accepted_sigma <= config.max_sigma &&
        std::isfinite(config.initial_inlier_quantile) &&
        config.initial_inlier_quantile > 0.0 && config.initial_inlier_quantile < 1.0 &&
        config.ransac_iterations > 0 && std::isfinite(config.ransac_residual_threshold) &&
        config.ransac_residual_threshold > 0.0 && std::isfinite(config.sigma_multiplier) &&
        config.sigma_multiplier > 0.0 && std::isfinite(config.min_ground_support) &&
        config.min_ground_support > 0.0 && config.min_ground_support <= 1.0 &&
        std::isfinite(config.min_bottom_support) && config.min_bottom_support > 0.0 &&
        config.min_bottom_support <= 1.0 && std::isfinite(config.discontinuity_threshold) &&
        config.discontinuity_threshold > 0.0 &&
        std::isfinite(config.discontinuity_max_threshold) &&
        config.discontinuity_max_threshold > config.discontinuity_threshold &&
        std::isfinite(config.max_discontinuity_support) &&
        config.max_discontinuity_support > 0.0 && config.max_discontinuity_support <= 1.0 &&
        config.min_candidate_count > 2 && std::isfinite(config.min_depth) &&
        std::isfinite(config.fit_max_depth) && config.min_depth > 0.0 &&
        config.fit_max_depth > config.min_depth;
}

bool IsFitDepth(float depth, const MleGroundConfig& config) {
    return std::isfinite(depth) && depth >= config.min_depth && depth <= config.fit_max_depth;
}

}  // namespace

GroundPlaneFitResult GroundPlaneWorkspace::Fit(
    const float* depth,
    int width,
    int height,
    const MleGroundConfig& config
) {
    GroundPlaneFitResult result;
    if (depth == nullptr || width <= 0 || height <= 0 || !IsValidConfig(config)) {
        return result;
    }

    if (coordinates_ == nullptr || coordinate_width_ != width || coordinate_height_ != height) {
        coordinates_ = std::make_unique<NormalizedCoordinateTable>(width, height);
        coordinate_width_ = width;
        coordinate_height_ = height;
    }

    const int rounded_roi = static_cast<int>(
        std::nearbyint(static_cast<double>(height) * config.fit_roi_top));
    const int roi_start = std::min(height - 1, std::max(0, rounded_roi));
    const std::size_t sampled_rows = static_cast<std::size_t>(
        (height - roi_start + config.sample_step - 1) / config.sample_step);
    const std::size_t sampled_columns = static_cast<std::size_t>(
        (width + config.sample_step - 1) / config.sample_step);
    const std::size_t sampled_capacity = sampled_rows * sampled_columns;
    if (candidate_x_.capacity() < sampled_capacity) {
        candidate_x_.reserve(sampled_capacity);
        candidate_y_.reserve(sampled_capacity);
        response_.reserve(sampled_capacity);
    }
    candidate_x_.clear();
    candidate_y_.clear();
    response_.clear();

    const auto& normalized_x = coordinates_->x();
    const auto& normalized_y = coordinates_->y();
    for (int row = roi_start; row < height; row += config.sample_step) {
        for (int column = 0; column < width; column += config.sample_step) {
            const std::size_t index = static_cast<std::size_t>(row) * width + column;
            if (!IsFitDepth(depth[index], config)) continue;
            candidate_x_.push_back(normalized_x[index]);
            candidate_y_.push_back(normalized_y[index]);
            response_.push_back(1.0 / static_cast<double>(depth[index]));
        }
    }

    const std::size_t candidate_count = response_.size();
    result.model.candidate_count = candidate_count;
    if (candidate_count < config.min_candidate_count) {
        result.status = GroundFitStatus::kInsufficientCandidates;
        return result;
    }

    PlaneCoefficients coefficients;
    std::size_t initial_inlier_count = 0;
    if (!FitPlaneRansac(
            candidate_x_.data(),
            candidate_y_.data(),
            response_.data(),
            candidate_count,
            config.ransac_iterations,
            config.ransac_residual_threshold,
            config.initial_inlier_quantile,
            &ransac_workspace_,
            &coefficients,
            &initial_inlier_count
        )) {
        result.status = GroundFitStatus::kRansacFailed;
        return result;
    }

    const double initial_support =
        static_cast<double>(initial_inlier_count) / static_cast<double>(candidate_count);
    result.ground_support = initial_support;
    if (initial_support < config.min_ground_support) {
        result.status = GroundFitStatus::kInsufficientInitialSupport;
        return result;
    }

    residuals_.resize(candidate_count);
    for (std::size_t index = 0; index < candidate_count; ++index) {
        residuals_[index] = response_[index] -
            (coefficients.a * candidate_x_[index] + coefficients.b * candidate_y_[index] +
             coefficients.c);
    }
    double sigma = EstimateMadSigma(
        residuals_.data(),
        ransac_workspace_.inliers().data(),
        candidate_count,
        config.min_sigma,
        config.max_sigma,
        &sigma_scratch_);
    const double sigma_ceiling = std::min(
        config.max_sigma,
        std::max(config.ransac_residual_threshold, sigma * 3.0));
    double ground_prior = std::clamp(initial_support, 0.15, 0.95);
    const auto response_bounds = std::minmax_element(response_.begin(), response_.end());
    const double response_span = std::max(
        *response_bounds.second - *response_bounds.first,
        config.min_sigma * 6.0);
    const double outlier_density = 1.0 / response_span;
    posterior_.resize(candidate_count);
    int completed_iterations = 0;

    for (int iteration = 0; iteration < config.max_iterations; ++iteration) {
        for (std::size_t index = 0; index < candidate_count; ++index) {
            residuals_[index] = response_[index] -
                (coefficients.a * candidate_x_[index] + coefficients.b * candidate_y_[index] +
                 coefficients.c);
            posterior_[index] = GroundPosterior(
                residuals_[index], sigma, ground_prior, outlier_density);
        }

        PlaneCoefficients updated_coefficients;
        if (!WeightedLeastSquares(
                candidate_x_.data(),
                candidate_y_.data(),
                response_.data(),
                posterior_.data(),
                candidate_count,
                &updated_coefficients
            )) {
            result.status = GroundFitStatus::kRansacFailed;
            return result;
        }

        double weight_sum = 0.0;
        double weighted_squared_residual = 0.0;
        double posterior_sum = 0.0;
        for (std::size_t index = 0; index < candidate_count; ++index) {
            const double updated_residual = response_[index] -
                (updated_coefficients.a * candidate_x_[index] +
                 updated_coefficients.b * candidate_y_[index] + updated_coefficients.c);
            weight_sum += posterior_[index];
            weighted_squared_residual +=
                posterior_[index] * updated_residual * updated_residual;
            posterior_sum += posterior_[index];
        }
        weight_sum = std::max(weight_sum, 1e-8);
        const double updated_sigma = std::clamp(
            std::sqrt(weighted_squared_residual / weight_sum),
            config.min_sigma,
            sigma_ceiling);
        const double updated_prior = std::clamp(
            posterior_sum / static_cast<double>(candidate_count), 0.10, 0.95);
        const double change = std::max({
            std::abs(updated_coefficients.a - coefficients.a),
            std::abs(updated_coefficients.b - coefficients.b),
            std::abs(updated_coefficients.c - coefficients.c),
            std::abs(updated_sigma - sigma),
            std::abs(updated_prior - ground_prior),
        });
        coefficients = updated_coefficients;
        sigma = updated_sigma;
        ground_prior = updated_prior;
        completed_iterations = iteration + 1;
        if (change < config.convergence_tolerance) break;
    }

    const double final_threshold = std::max(
        config.ransac_residual_threshold, config.sigma_multiplier * sigma);
    std::size_t final_inlier_count = 0;
    std::size_t bottom_candidate_count = 0;
    std::size_t bottom_inlier_count = 0;
    for (std::size_t index = 0; index < candidate_count; ++index) {
        const double residual = std::abs(
            response_[index] -
            (coefficients.a * candidate_x_[index] + coefficients.b * candidate_y_[index] +
             coefficients.c));
        const bool is_inlier = residual <= final_threshold;
        final_inlier_count += is_inlier ? 1U : 0U;
        if (candidate_y_[index] >= 0.85) {
            ++bottom_candidate_count;
            bottom_inlier_count += is_inlier ? 1U : 0U;
        }
    }
    result.ground_support =
        static_cast<double>(final_inlier_count) / static_cast<double>(candidate_count);
    result.bottom_support = bottom_candidate_count == 0
        ? 0.0
        : static_cast<double>(bottom_inlier_count) / static_cast<double>(bottom_candidate_count);
    result.model = {
        coefficients,
        sigma,
        ground_prior,
        outlier_density,
        completed_iterations,
        candidate_count,
    };

    if (!std::isfinite(coefficients.a) || !std::isfinite(coefficients.b) ||
        !std::isfinite(coefficients.c)) {
        result.status = GroundFitStatus::kNonFiniteModel;
        return result;
    }
    if (coefficients.b <= 0.0) {
        result.status = GroundFitStatus::kNonPositiveSlope;
        return result;
    }
    if (sigma > config.max_accepted_sigma) {
        result.status = GroundFitStatus::kSigmaTooLarge;
        return result;
    }
    if (result.ground_support < config.min_ground_support) {
        result.status = GroundFitStatus::kInsufficientGroundSupport;
        return result;
    }
    if (result.bottom_support < config.min_bottom_support) {
        result.status = GroundFitStatus::kInsufficientBottomSupport;
        return result;
    }
    if (HasSupportedDiscontinuity(
            depth, width, height, roi_start, config, coefficients)) {
        result.status = GroundFitStatus::kSupportedDiscontinuity;
        return result;
    }

    result.status = GroundFitStatus::kSucceeded;
    return result;
}

bool GroundPlaneWorkspace::HasSupportedDiscontinuity(
    const float* depth,
    int width,
    int height,
    int roi_start,
    const MleGroundConfig& config,
    const PlaneCoefficients& coefficients
) const {
    if (width < 2) return false;
    const auto& normalized_x = coordinates_->x();
    const auto& normalized_y = coordinates_->y();
    for (int left_column = 0; left_column + config.sample_step < width;
         left_column += config.sample_step) {
        const int right_column = left_column + config.sample_step;
        std::size_t pair_count = 0;
        std::size_t supported_count = 0;
        for (int row = roi_start; row < height; row += config.sample_step) {
            const std::size_t left_index = static_cast<std::size_t>(row) * width + left_column;
            const std::size_t right_index = static_cast<std::size_t>(row) * width + right_column;
            if (!IsFitDepth(depth[left_index], config) ||
                !IsFitDepth(depth[right_index], config)) {
                continue;
            }
            ++pair_count;
            const double left_residual = 1.0 / static_cast<double>(depth[left_index]) -
                (coefficients.a * normalized_x[left_index] +
                 coefficients.b * normalized_y[left_index] + coefficients.c);
            const double right_residual = 1.0 / static_cast<double>(depth[right_index]) -
                (coefficients.a * normalized_x[right_index] +
                 coefficients.b * normalized_y[right_index] + coefficients.c);
            const double jump = std::abs(right_residual - left_residual);
            if (jump > config.discontinuity_threshold &&
                jump <= config.discontinuity_max_threshold) {
                ++supported_count;
            }
        }
        if (pair_count > 0 &&
            static_cast<double>(supported_count) / static_cast<double>(pair_count) >=
                config.max_discontinuity_support) {
            return true;
        }
    }
    return false;
}

void GroundPlaneWorkspace::Reset() {
    candidate_x_.clear();
    candidate_y_.clear();
    response_.clear();
    posterior_.clear();
    residuals_.clear();
    sigma_scratch_.clear();
}

namespace {

bool IsValidClassificationConfig(const GroundClassificationConfig& config) {
    return std::isfinite(config.classification_roi_top) &&
        config.classification_roi_top >= 0.0 && config.classification_roi_top < 1.0 &&
        std::isfinite(config.obstacle_enter_depth) && config.obstacle_enter_depth > 0.0 &&
        std::isfinite(config.obstacle_exit_depth) &&
        config.obstacle_exit_depth > config.obstacle_enter_depth &&
        std::isfinite(config.emergency_depth) && config.emergency_depth > 0.0 &&
        config.emergency_depth < config.obstacle_enter_depth &&
        std::isfinite(config.posterior_threshold) && config.posterior_threshold > 0.0 &&
        config.posterior_threshold < 1.0 && config.morphology_kernel > 0 &&
        config.morphology_kernel % 2 == 1 && std::isfinite(config.bottom_seed_fraction) &&
        config.bottom_seed_fraction > 0.0 && config.bottom_seed_fraction <= 1.0;
}

std::size_t ObstacleDepthHistogramBin(float depth, double exit_depth) {
    const double normalized = std::clamp(
        static_cast<double>(depth) / exit_depth,
        0.0,
        1.0
    );
    return std::min(
        static_cast<std::size_t>(normalized * kObstacleDepthHistogramBins),
        kObstacleDepthHistogramBins - 1U
    );
}

float ObstacleNearPercentileMeters(
    const std::uint32_t* histogram,
    std::size_t count,
    double exit_depth
) {
    if (count == 0U) return 0.0F;
    constexpr double kNearPercentile = 0.20;
    const std::size_t target = std::max<std::size_t>(
        1U,
        static_cast<std::size_t>(std::ceil(static_cast<double>(count) * kNearPercentile))
    );
    std::size_t cumulative = 0U;
    for (std::size_t bin = 0; bin < kObstacleDepthHistogramBins; ++bin) {
        cumulative += histogram[bin];
        if (cumulative >= target) {
            const double center = (static_cast<double>(bin) + 0.5) /
                static_cast<double>(kObstacleDepthHistogramBins);
            return static_cast<float>(center * exit_depth);
        }
    }
    return static_cast<float>(exit_depth);
}

double PosteriorResidualLimit(
    const GroundPlaneModel& model,
    double posterior_threshold
) {
    constexpr double kSqrtTwoPi = 2.5066282746310005024;
    const double denominator =
        model.ground_prior * (1.0 - posterior_threshold);
    if (model.sigma <= 0.0 || denominator <= 0.0) return -1.0;
    const double density_ratio =
        posterior_threshold * (1.0 - model.ground_prior) * model.outlier_density *
        kSqrtTwoPi * model.sigma / denominator;
    if (density_ratio <= 0.0) return std::numeric_limits<double>::infinity();
    if (density_ratio > 1.0) return -1.0;
    return model.sigma * std::sqrt(-2.0 * std::log(density_ratio));
}

}  // namespace

GroundClassificationResult GroundClassificationWorkspace::Classify(
    const float* depth,
    int width,
    int height,
    const MleGroundConfig& fit_config,
    const GroundClassificationConfig& classification_config,
    const GroundPlaneFitResult& fit,
    const NormalizedCoordinateTable& coordinates,
    bool write_class_map
) {
    GroundClassificationResult result;
    result.fit_succeeded = fit.succeeded();
    obstacle_counts_.fill(0U);
    obstacle_depth_histogram_.fill(0U);
    obstacle_occupancy_.fill(0.0F);
    obstacle_distance_meters_.fill(0.0F);
    if (depth == nullptr || width <= 0 || height <= 0 ||
        coordinates.width() != width || coordinates.height() != height ||
        !IsValidConfig(fit_config) || !IsValidClassificationConfig(classification_config) ||
        classification_config.obstacle_exit_depth > fit_config.fit_max_depth) {
        class_map_.clear();
        return result;
    }

    PrepareGridMapping(width, height);

    const std::size_t pixel_count = static_cast<std::size_t>(width) * height;
    if (obstacle_range_active_mask_.size() != pixel_count) {
        obstacle_range_active_mask_.assign(pixel_count, 0U);
        current_obstacle_mask_.assign(pixel_count, 0U);
        previous_obstacle_mask_.assign(pixel_count, 0U);
        older_obstacle_mask_.assign(pixel_count, 0U);
    }
    if (write_class_map) {
        class_map_.resize(pixel_count);
    } else {
        class_map_.clear();
    }

    const int classification_roi_start = std::min(
        height - 1,
        std::max(0, static_cast<int>(std::nearbyint(
            static_cast<double>(height) * classification_config.classification_roi_top))));
    const int fit_roi_start = std::min(
        height - 1,
        std::max(0, static_cast<int>(std::nearbyint(
            static_cast<double>(height) * fit_config.fit_roi_top))));
    const auto& normalized_x = coordinates.x();
    const auto& normalized_y = coordinates.y();

    if (fit.succeeded()) {
        plane_residuals_.resize(pixel_count);
        candidate_mask_.resize(pixel_count);
        eroded_mask_.resize(pixel_count);
        opened_mask_.resize(pixel_count);
        ground_mask_.resize(pixel_count);
        std::fill(candidate_mask_.begin(), candidate_mask_.end(), 0U);
        const GroundPlaneModel& model = fit.model;
        column_plane_terms_.resize(static_cast<std::size_t>(width));
        row_plane_terms_.resize(static_cast<std::size_t>(height));
        for (int column = 0; column < width; ++column) {
            column_plane_terms_[column] = static_cast<float>(
                model.coefficients.a * normalized_x[column]);
        }
        for (int row = 0; row < height; ++row) {
            row_plane_terms_[row] = static_cast<float>(
                model.coefficients.b * normalized_y[static_cast<std::size_t>(row) * width] +
                model.coefficients.c);
        }
        const float residual_limit = static_cast<float>(
            fit_config.sigma_multiplier * model.sigma);
        const double posterior_residual_limit = PosteriorResidualLimit(
            model,
            classification_config.posterior_threshold);
        const float candidate_residual_limit = std::min(
            residual_limit,
            static_cast<float>(posterior_residual_limit));
        for (int row = classification_roi_start; row < height; ++row) {
            const std::size_t row_offset = static_cast<std::size_t>(row) * width;
            const float row_plane_term = row_plane_terms_[row];
            for (int column = 0; column < width; ++column) {
                const std::size_t index = row_offset + column;
                if (!IsFitDepth(depth[index], fit_config)) continue;
                const float predicted = column_plane_terms_[column] + row_plane_term;
                const float residual = 1.0F / depth[index] - predicted;
                plane_residuals_[index] = residual;
                candidate_mask_[index] =
                    std::abs(residual) <= candidate_residual_limit
                    ? 1U
                    : 0U;
            }
        }
        MorphologicalOpen(width, height, classification_config.morphology_kernel);
        MarkBottomConnected(width, height, classification_config.bottom_seed_fraction);
    }

    std::size_t ground_count = 0;
    std::size_t obstacle_count = 0;
    std::size_t unknown_count = 0;
    const float closer_residual_limit = fit.succeeded()
        ? static_cast<float>(fit_config.sigma_multiplier * fit.model.sigma)
        : 0.0F;
    for (int row = 0; row < height; ++row) {
        const std::size_t row_offset = static_cast<std::size_t>(row) * width;
        const std::size_t grid_row_offset =
            static_cast<std::size_t>(row_to_grid_[row]) * kObstacleGridSize;
        for (int column = 0; column < width; ++column) {
            const std::size_t index = row_offset + column;
            GroundClass classification = GroundClass::kInvalid;
            if (IsFinitePositiveDepth(depth[index])) {
                if (row < classification_roi_start) {
                    classification = GroundClass::kUnknown;
                    obstacle_range_active_mask_[index] = 0U;
                } else if (!fit.succeeded()) {
                    const double range_limit = obstacle_range_active_mask_[index] != 0U
                        ? classification_config.obstacle_exit_depth
                        : classification_config.obstacle_enter_depth;
                    if (depth[index] <= range_limit) {
                        classification = GroundClass::kObstacle;
                        obstacle_range_active_mask_[index] = 1U;
                    } else {
                        classification = GroundClass::kUnknown;
                        obstacle_range_active_mask_[index] = 0U;
                    }
                } else if (ground_mask_[index] != 0U) {
                    classification = GroundClass::kGround;
                    obstacle_range_active_mask_[index] = 0U;
                } else {
                    const bool is_obstacle_candidate =
                        row < fit_roi_start || depth[index] < fit_config.min_depth ||
                        (depth[index] <= fit_config.fit_max_depth &&
                         plane_residuals_[index] > closer_residual_limit);
                    const double range_limit = obstacle_range_active_mask_[index] != 0U
                        ? classification_config.obstacle_exit_depth
                        : classification_config.obstacle_enter_depth;
                    if (is_obstacle_candidate && depth[index] <= range_limit) {
                        classification = GroundClass::kObstacle;
                        obstacle_range_active_mask_[index] = 1U;
                    } else {
                        classification = GroundClass::kUnknown;
                        obstacle_range_active_mask_[index] = 0U;
                    }
                }
            } else {
                obstacle_range_active_mask_[index] = 0U;
            }
            const bool raw_obstacle = classification == GroundClass::kObstacle;
            current_obstacle_mask_[index] = raw_obstacle ? 1U : 0U;
            const unsigned int obstacle_votes =
                static_cast<unsigned int>(current_obstacle_mask_[index]) +
                static_cast<unsigned int>(previous_obstacle_mask_[index]) +
                static_cast<unsigned int>(older_obstacle_mask_[index]);
            const bool emergency_obstacle =
                raw_obstacle && depth[index] <= classification_config.emergency_depth;
            const bool stable_obstacle =
                IsFinitePositiveDepth(depth[index]) &&
                (emergency_obstacle || obstacle_votes >= 2U);
            if (stable_obstacle) {
                classification = GroundClass::kObstacle;
            } else if (raw_obstacle) {
                classification = GroundClass::kUnknown;
            }
            if (write_class_map) {
                class_map_[index] = static_cast<std::uint8_t>(classification);
            }
            const std::size_t grid_cell =
                grid_row_offset + column_to_grid_[column];
            if (classification == GroundClass::kObstacle) {
                ++obstacle_counts_[grid_cell];
                const std::size_t bin = ObstacleDepthHistogramBin(
                    depth[index],
                    classification_config.obstacle_exit_depth
                );
                ++obstacle_depth_histogram_[
                    grid_cell * kObstacleDepthHistogramBins + bin
                ];
            }
            ground_count += classification == GroundClass::kGround ? 1U : 0U;
            obstacle_count += classification == GroundClass::kObstacle ? 1U : 0U;
            unknown_count += classification == GroundClass::kUnknown ? 1U : 0U;
        }
    }

    const double denominator = static_cast<double>(pixel_count);
    result.ground_fraction = static_cast<double>(ground_count) / denominator;
    result.obstacle_fraction = static_cast<double>(obstacle_count) / denominator;
    result.unknown_fraction = static_cast<double>(unknown_count) / denominator;
    for (std::size_t cell = 0; cell < kObstacleGridCellCount; ++cell) {
        obstacle_occupancy_[cell] = total_counts_[cell] == 0
            ? 0.0F
            : std::clamp(
                static_cast<float>(obstacle_counts_[cell]) /
                    static_cast<float>(total_counts_[cell]),
                0.0F,
                1.0F);
        obstacle_distance_meters_[cell] = ObstacleNearPercentileMeters(
            obstacle_depth_histogram_.data() + cell * kObstacleDepthHistogramBins,
            obstacle_counts_[cell],
            classification_config.obstacle_exit_depth
        );
    }
    older_obstacle_mask_.swap(previous_obstacle_mask_);
    previous_obstacle_mask_.swap(current_obstacle_mask_);
    return result;
}

void GroundClassificationWorkspace::PrepareGridMapping(int width, int height) {
    if (grid_mapping_width_ == width && grid_mapping_height_ == height) return;
    grid_mapping_width_ = width;
    grid_mapping_height_ = height;
    column_to_grid_.resize(static_cast<std::size_t>(width));
    row_to_grid_.resize(static_cast<std::size_t>(height));
    for (int column = 0; column < width; ++column) {
        const std::uint64_t numerator =
            (static_cast<std::uint64_t>(column) + 1U) * kObstacleGridSize - 1U;
        column_to_grid_[column] = static_cast<std::uint8_t>(
            std::min<std::uint64_t>(numerator / static_cast<std::uint64_t>(width),
                                    kObstacleGridSize - 1U));
    }
    for (int row = 0; row < height; ++row) {
        const std::uint64_t numerator =
            (static_cast<std::uint64_t>(row) + 1U) * kObstacleGridSize - 1U;
        row_to_grid_[row] = static_cast<std::uint8_t>(
            std::min<std::uint64_t>(numerator / static_cast<std::uint64_t>(height),
                                    kObstacleGridSize - 1U));
    }
    total_counts_.fill(0U);
    for (int row = 0; row < height; ++row) {
        const std::size_t grid_row_offset =
            static_cast<std::size_t>(row_to_grid_[row]) * kObstacleGridSize;
        for (int column = 0; column < width; ++column) {
            ++total_counts_[grid_row_offset + column_to_grid_[column]];
        }
    }
}

void GroundClassificationWorkspace::MorphologicalOpen(
    int width,
    int height,
    int kernel_size
) {
    if (kernel_size <= 1) {
        opened_mask_ = candidate_mask_;
        return;
    }
    const int radius = kernel_size / 2;
    std::fill(eroded_mask_.begin(), eroded_mask_.end(), 0U);
    std::fill(opened_mask_.begin(), opened_mask_.end(), 0U);

    if (kernel_size == 3) {
        for (int row = 0; row < height; ++row) {
            const std::size_t row_offset = static_cast<std::size_t>(row) * width;
            for (int column = 0; column < width; ++column) {
                const std::size_t index = row_offset + column;
                const bool survives = candidate_mask_[index] != 0U &&
                    (row == 0 || candidate_mask_[index - width] != 0U) &&
                    (row + 1 == height || candidate_mask_[index + width] != 0U) &&
                    (column == 0 || candidate_mask_[index - 1U] != 0U) &&
                    (column + 1 == width || candidate_mask_[index + 1U] != 0U);
                eroded_mask_[index] = survives ? 1U : 0U;
            }
        }
        for (int row = 0; row < height; ++row) {
            const std::size_t row_offset = static_cast<std::size_t>(row) * width;
            for (int column = 0; column < width; ++column) {
                const std::size_t index = row_offset + column;
                const bool included = eroded_mask_[index] != 0U ||
                    (row > 0 && eroded_mask_[index - width] != 0U) ||
                    (row + 1 < height && eroded_mask_[index + width] != 0U) ||
                    (column > 0 && eroded_mask_[index - 1U] != 0U) ||
                    (column + 1 < width && eroded_mask_[index + 1U] != 0U);
                opened_mask_[index] = included ? 1U : 0U;
            }
        }
        return;
    }

    for (int row = 0; row < height; ++row) {
        for (int column = 0; column < width; ++column) {
            const std::size_t index = static_cast<std::size_t>(row) * width + column;
            if (candidate_mask_[index] == 0U) continue;
            bool survives = true;
            for (int delta_y = -radius; delta_y <= radius && survives; ++delta_y) {
                for (int delta_x = -radius; delta_x <= radius; ++delta_x) {
                    if (delta_x * delta_x + delta_y * delta_y > radius * radius) continue;
                    const int neighbor_row = row + delta_y;
                    const int neighbor_column = column + delta_x;
                    if (neighbor_row < 0 || neighbor_row >= height || neighbor_column < 0 ||
                        neighbor_column >= width) {
                        continue;
                    }
                    const std::size_t neighbor =
                        static_cast<std::size_t>(neighbor_row) * width + neighbor_column;
                    if (candidate_mask_[neighbor] == 0U) {
                        survives = false;
                        break;
                    }
                }
            }
            eroded_mask_[index] = survives ? 1U : 0U;
        }
    }

    for (int row = 0; row < height; ++row) {
        for (int column = 0; column < width; ++column) {
            bool included = false;
            for (int delta_y = -radius; delta_y <= radius && !included; ++delta_y) {
                for (int delta_x = -radius; delta_x <= radius; ++delta_x) {
                    if (delta_x * delta_x + delta_y * delta_y > radius * radius) continue;
                    const int neighbor_row = row + delta_y;
                    const int neighbor_column = column + delta_x;
                    if (neighbor_row < 0 || neighbor_row >= height || neighbor_column < 0 ||
                        neighbor_column >= width) {
                        continue;
                    }
                    const std::size_t neighbor =
                        static_cast<std::size_t>(neighbor_row) * width + neighbor_column;
                    if (eroded_mask_[neighbor] != 0U) {
                        included = true;
                        break;
                    }
                }
            }
            opened_mask_[static_cast<std::size_t>(row) * width + column] =
                included ? 1U : 0U;
        }
    }
}

void GroundClassificationWorkspace::MarkBottomConnected(
    int width,
    int height,
    double bottom_seed_fraction
) {
    std::fill(ground_mask_.begin(), ground_mask_.end(), 0U);
    flood_queue_.clear();
    if (flood_queue_.capacity() < opened_mask_.size()) {
        flood_queue_.reserve(opened_mask_.size());
    }
    const int seed_height = std::max(
        1,
        static_cast<int>(std::nearbyint(static_cast<double>(height) * bottom_seed_fraction)));
    const int seed_start = std::max(0, height - seed_height);
    for (int row = seed_start; row < height; ++row) {
        for (int column = 0; column < width; ++column) {
            const std::size_t index = static_cast<std::size_t>(row) * width + column;
            if (opened_mask_[index] != 0U && ground_mask_[index] == 0U) {
                ground_mask_[index] = 1U;
                flood_queue_.push_back({static_cast<std::uint32_t>(index), column});
            }
        }
    }

    const std::uint32_t pixel_count = static_cast<std::uint32_t>(
        static_cast<std::size_t>(width) * height);
    const std::uint32_t row_stride = static_cast<std::uint32_t>(width);
    const auto enqueue = [this](std::uint32_t index, std::int32_t column) {
        if (opened_mask_[index] == 0U || ground_mask_[index] != 0U) return;
        ground_mask_[index] = 1U;
        flood_queue_.push_back({index, column});
    };
    std::size_t queue_index = 0;
    while (queue_index < flood_queue_.size()) {
        const FloodPixel current = flood_queue_[queue_index++];
        const bool has_left = current.column > 0;
        const bool has_right = current.column + 1 < width;
        if (current.index >= row_stride) {
            const std::uint32_t upper = current.index - row_stride;
            if (has_left) enqueue(upper - 1U, current.column - 1);
            enqueue(upper, current.column);
            if (has_right) enqueue(upper + 1U, current.column + 1);
        }
        if (has_left) enqueue(current.index - 1U, current.column - 1);
        if (has_right) enqueue(current.index + 1U, current.column + 1);
        if (current.index + row_stride < pixel_count) {
            const std::uint32_t lower = current.index + row_stride;
            if (has_left) enqueue(lower - 1U, current.column - 1);
            enqueue(lower, current.column);
            if (has_right) enqueue(lower + 1U, current.column + 1);
        }
    }
}

void GroundClassificationWorkspace::Reset() {
    plane_residuals_.clear();
    column_plane_terms_.clear();
    row_plane_terms_.clear();
    candidate_mask_.clear();
    eroded_mask_.clear();
    opened_mask_.clear();
    ground_mask_.clear();
    obstacle_range_active_mask_.clear();
    current_obstacle_mask_.clear();
    previous_obstacle_mask_.clear();
    older_obstacle_mask_.clear();
    class_map_.clear();
    flood_queue_.clear();
    obstacle_counts_.fill(0U);
    total_counts_.fill(0U);
    obstacle_depth_histogram_.fill(0U);
    obstacle_occupancy_.fill(0.0F);
    obstacle_distance_meters_.fill(0.0F);
    grid_mapping_width_ = 0;
    grid_mapping_height_ = 0;
    column_to_grid_.clear();
    row_to_grid_.clear();
}

}  // namespace glasses::ground
