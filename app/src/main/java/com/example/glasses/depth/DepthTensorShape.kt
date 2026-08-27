package com.example.glasses.depth

data class DepthTensorShape(
    val width: Int,
    val height: Int,
) {
    companion object {
        fun parse(dimensions: IntArray, elementCount: Int): DepthTensorShape {
            require(dimensions.isNotEmpty()) { "Depth output dimensions are empty" }
            require(elementCount > 0) { "Depth output element count must be positive" }

            val height: Int
            val width: Int
            when {
                dimensions.size == 4 && dimensions[0] == 1 && dimensions[1] == 1 -> {
                    height = dimensions[2]
                    width = dimensions[3]
                }

                dimensions.size == 4 && dimensions[0] == 1 && dimensions[3] == 1 -> {
                    height = dimensions[1]
                    width = dimensions[2]
                }

                dimensions.size == 3 && dimensions[0] == 1 -> {
                    height = dimensions[1]
                    width = dimensions[2]
                }

                dimensions.size == 2 -> {
                    height = dimensions[0]
                    width = dimensions[1]
                }

                else -> throw IllegalArgumentException(
                    "Unsupported depth output shape: ${dimensions.joinToString(prefix = "[", postfix = "]")}",
                )
            }

            require(width > 0 && height > 0) { "Depth width and height must be positive" }
            require(width * height == elementCount) {
                "Depth shape ${width}x$height does not match $elementCount output values"
            }
            return DepthTensorShape(width = width, height = height)
        }
    }
}
