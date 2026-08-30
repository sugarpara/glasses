#ifndef GLASSES_GROUND_FILTER_H_
#define GLASSES_GROUND_FILTER_H_

#include <array>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <vector>

namespace glasses::ground {

constexpr std::size_t kObstacleGridSize = 64;
constexpr std::size_t kObstacleGridCellCount = kObstacleGridSize * kObstacleGridSize;
constexpr std::size_t kObstacleDepthHistogramBins = 16;

struct PlaneCoefficients {
    double a = 0.0;
    double b = 0.0;
    double c = 0.0;
};

bool IsFinitePositiveDepth(float depth);

double InverseDepthOrNaN(float depth);

class NormalizedCoordinateTable {
public:
    NormalizedCoordinateTable(int width, int height);

    int width() const { return width_; }
    int height() const { return height_; }
    const std::vector<double>& x() const { return x_; }
    const std::vector<double>& y() const { return y_; }

private:
    int width_;
    int height_;
    std::vector<double> x_;
    std::vector<double> y_;
};

bool SolveThreeByThree(
    const std::array<double, 9>& matrix,
    const std::array<double, 3>& right_hand_side,
    std::array<double, 3>* solution
);

bool WeightedLeastSquares(
    const double* x,
    const double* y,
    const double* response,
    const double* weights,
    std::size_t count,
    PlaneCoefficients* coefficients
);

class RansacWorkspace {
public:
    void Resize(std::size_t count);

    const std::vector<std::uint8_t>& inliers() const { return inliers_; }

private:
    bool FitTrimmedPlane(
        const double* x,
        const double* y,
        const double* response,
        std::size_t count,
        double inlier_quantile,
        PlaneCoefficients* coefficients,
        std::size_t* final_inlier_count
    );

    friend bool FitPlaneRansac(
        const double*,
        const double*,
        const double*,
        std::size_t,
        int,
        double,
        double,
        RansacWorkspace*,
        PlaneCoefficients*,
        std::size_t*
    );

    std::vector<double> residuals_;
    std::vector<double> sorted_residuals_;
    std::vector<std::uint8_t> inliers_;
    std::vector<std::uint8_t> next_inliers_;
};

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
);

double EstimateMadSigma(
    const double* residuals,
    const std::uint8_t* inliers,
    std::size_t count,
    double min_sigma,
    double max_sigma,
    std::vector<double>* scratch
);

double NormalDensity(double residual, double sigma);

double GroundPosterior(
    double residual,
    double sigma,
    double ground_prior,
    double outlier_density
);

struct MleGroundConfig {
    double fit_roi_top = 0.45;
    int sample_step = 4;
    int max_iterations = 20;
    double convergence_tolerance = 1e-5;
    double min_sigma = 0.008;
    double max_sigma = 0.18;
    double max_accepted_sigma = 0.020;
    double initial_inlier_quantile = 0.65;
    int ransac_iterations = 64;
    double ransac_residual_threshold = 0.025;
    double sigma_multiplier = 3.0;
    double min_ground_support = 0.12;
    double min_bottom_support = 0.20;
    double discontinuity_threshold = 0.025;
    double discontinuity_max_threshold = 0.12;
    double max_discontinuity_support = 0.55;
    std::size_t min_candidate_count = 500;
    double min_depth = 0.1;
    double fit_max_depth = 30.0;
};

struct GroundPlaneModel {
    PlaneCoefficients coefficients;
    double sigma = 0.0;
    double ground_prior = 0.0;
    double outlier_density = 0.0;
    int iterations = 0;
    std::size_t candidate_count = 0;
};

enum class GroundFitStatus {
    kSucceeded = 0,
    kInvalidInput = 1,
    kInsufficientCandidates = 2,
    kRansacFailed = 3,
    kInsufficientInitialSupport = 4,
    kNonFiniteModel = 5,
    kNonPositiveSlope = 6,
    kSigmaTooLarge = 7,
    kInsufficientGroundSupport = 8,
    kInsufficientBottomSupport = 9,
    kSupportedDiscontinuity = 10,
};

struct GroundPlaneFitResult {
    GroundFitStatus status = GroundFitStatus::kInvalidInput;
    GroundPlaneModel model;
    double ground_support = 0.0;
    double bottom_support = 0.0;

    bool succeeded() const { return status == GroundFitStatus::kSucceeded; }
};

class GroundPlaneWorkspace {
public:
    GroundPlaneFitResult Fit(
        const float* depth,
        int width,
        int height,
        const MleGroundConfig& config
    );

    // Keeps allocated capacity so reset does not cause allocation churn on the next frame.
    void Reset();

    const NormalizedCoordinateTable* coordinates() const { return coordinates_.get(); }

private:
    bool HasSupportedDiscontinuity(
        const float* depth,
        int width,
        int height,
        int roi_start,
        const MleGroundConfig& config,
        const PlaneCoefficients& coefficients
    ) const;

    int coordinate_width_ = 0;
    int coordinate_height_ = 0;
    std::unique_ptr<NormalizedCoordinateTable> coordinates_;
    std::vector<double> candidate_x_;
    std::vector<double> candidate_y_;
    std::vector<double> response_;
    std::vector<double> posterior_;
    std::vector<double> residuals_;
    std::vector<double> sigma_scratch_;
    RansacWorkspace ransac_workspace_;
};

enum class GroundClass : std::uint8_t {
    kInvalid = 0,
    kGround = 1,
    kObstacle = 2,
    kUnknown = 3,
};

struct GroundClassificationConfig {
    double classification_roi_top = 0.0;
    double obstacle_enter_depth = 3.0;
    double obstacle_exit_depth = 3.3;
    double emergency_depth = 0.8;
    double posterior_threshold = 0.55;
    int morphology_kernel = 3;
    double bottom_seed_fraction = 0.08;
};

struct GroundClassificationResult {
    bool fit_succeeded = false;
    double ground_fraction = 0.0;
    double obstacle_fraction = 0.0;
    double unknown_fraction = 0.0;
};

class GroundClassificationWorkspace {
public:
    GroundClassificationResult Classify(
        const float* depth,
        int width,
        int height,
        const MleGroundConfig& fit_config,
        const GroundClassificationConfig& classification_config,
        const GroundPlaneFitResult& fit,
        const NormalizedCoordinateTable& coordinates,
        bool write_class_map
    );

    const std::vector<std::uint8_t>& class_map() const { return class_map_; }
    const std::array<float, kObstacleGridCellCount>& obstacle_occupancy() const {
        return obstacle_occupancy_;
    }
    const std::array<float, kObstacleGridCellCount>& obstacle_distance_meters() const {
        return obstacle_distance_meters_;
    }

    // Keeps allocated capacity so reset does not cause allocation churn on the next frame.
    void Reset();

private:
    void MorphologicalOpen(int width, int height, int kernel_size);
    void MarkBottomConnected(int width, int height, double bottom_seed_fraction);
    void PrepareGridMapping(int width, int height);

    std::vector<float> plane_residuals_;
    std::vector<float> column_plane_terms_;
    std::vector<float> row_plane_terms_;
    std::vector<std::uint8_t> candidate_mask_;
    std::vector<std::uint8_t> eroded_mask_;
    std::vector<std::uint8_t> opened_mask_;
    std::vector<std::uint8_t> ground_mask_;
    std::vector<std::uint8_t> obstacle_range_active_mask_;
    std::vector<std::uint8_t> current_obstacle_mask_;
    std::vector<std::uint8_t> previous_obstacle_mask_;
    std::vector<std::uint8_t> older_obstacle_mask_;
    std::vector<std::uint8_t> class_map_;
    struct FloodPixel {
        std::uint32_t index;
        std::int32_t column;
    };
    std::vector<FloodPixel> flood_queue_;
    int grid_mapping_width_ = 0;
    int grid_mapping_height_ = 0;
    std::vector<std::uint8_t> column_to_grid_;
    std::vector<std::uint8_t> row_to_grid_;
    std::array<std::size_t, kObstacleGridCellCount> obstacle_counts_{};
    std::array<std::size_t, kObstacleGridCellCount> total_counts_{};
    std::array<std::uint32_t, kObstacleGridCellCount * kObstacleDepthHistogramBins>
        obstacle_depth_histogram_{};
    std::array<float, kObstacleGridCellCount> obstacle_occupancy_{};
    std::array<float, kObstacleGridCellCount> obstacle_distance_meters_{};
};

}  // namespace glasses::ground

#endif  // GLASSES_GROUND_FILTER_H_
