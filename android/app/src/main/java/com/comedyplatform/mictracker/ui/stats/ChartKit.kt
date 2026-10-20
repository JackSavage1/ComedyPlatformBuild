package com.comedyplatform.mictracker.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// =============================================================================
// A tiny hand-drawn chart kit (spec section 1: "Hand-drawn Compose Canvas
// charts, no chart library").
//
//   VerticalBarChart    bars standing up, one color per bar
//   HorizontalBarChart  bars lying down, label on the left, value at the end
//   ScatterChart        dots in several colored series + an optional line
//   StackedBarChart     one stacked bar per category, one color per series
//   ChartLegend         colored dots/lines with labels (plain Compose, below a chart)
//
// Every chart draws a few gridlines with labelled ticks. Text inside a Canvas
// is laid out with a TextMeasurer and drawn with drawText().
// =============================================================================

/** Plotly's default qualitative palette — what the web dashboard used for venues and tags. */
val ChartPalette: List<Color> = listOf(
    Color(0xFF636EFA),
    Color(0xFFEF553B),
    Color(0xFF00CC96),
    Color(0xFFAB63FA),
    Color(0xFFFFA15A),
    Color(0xFF19D3F3),
    Color(0xFFFF6692),
    Color(0xFFB6E880),
    Color(0xFFFF97FF),
    Color(0xFFFECB52),
)

/** The palette color for series number [index] (wraps around). */
fun paletteColor(index: Int): Color {
    val size = ChartPalette.size
    return ChartPalette[((index % size) + size) % size]
}

/** One bar: its label, its value, and its color. */
data class ChartBar(val label: String, val value: Double, val color: Color)

/** One dot; [radius] lets a chart size dots by a third value (e.g. crowd size). */
data class ScatterPoint(val x: Double, val y: Double, val radius: Dp = 5.dp)

/** A colored group of dots (e.g. every set at one venue). */
data class ScatterSeries(val name: String, val color: Color, val points: List<ScatterPoint>)

/** A straight line in data coordinates (e.g. a trendline), drawn dashed. */
data class ChartLine(val startX: Double, val startY: Double, val endX: Double, val endY: Double, val color: Color)

/** One layer of a stacked bar chart; [values] line up with the chart's categories. */
data class StackedSeries(val name: String, val color: Color, val values: List<Double>)

/**
 * An axis: the visible range, where the labelled ticks go, and how to print
 * a tick value (e.g. a date for an epoch-day axis).
 */
class ChartAxis(
    val min: Double,
    val max: Double,
    val ticks: List<Double>,
    val label: (Double) -> String = { ChartMath.formatNumber(it) },
)

/** A legend item: a dot, or a short line when [isLine] (for a trendline). */
data class LegendEntry(val label: String, val color: Color, val isLine: Boolean = false)

/** sin(45°) = cos(45°): how much of a label's size a 45° rotation projects onto each axis. */
private const val DIAGONAL = 0.7071f

/** Longest a rotated x-axis label may get before it's cut with "…". */
private val MaxRotatedLabelWidth = 88.dp

/** The colors and text style every chart uses, read from the Material theme. */
private class ChartStyle(
    val text: TextStyle,
    val grid: Color,
    val axis: Color,
    val dotOutline: Color,
)

@Composable
private fun rememberChartStyle(): ChartStyle = ChartStyle(
    text = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
    grid = MaterialTheme.colorScheme.outlineVariant,
    axis = MaterialTheme.colorScheme.outline,
    dotOutline = MaterialTheme.colorScheme.surface,
)

// -----------------------------------------------------------------------------
// Vertical + stacked bars (they share one drawing routine)
// -----------------------------------------------------------------------------

/** One colored piece of a column. */
private class BarSegment(val value: Double, val color: Color)

/**
 * Bars standing up, Mon→Sun style. Labels that don't fit under their bar are
 * drawn at 45°. The value is printed above each bar when there's room.
 */
@Composable
fun VerticalBarChart(
    bars: List<ChartBar>,
    modifier: Modifier = Modifier,
    height: Dp = 220.dp,
    showValues: Boolean = true,
) {
    ColumnChart(
        categories = bars.map { it.label },
        stacks = bars.map { bar -> listOf(BarSegment(bar.value, bar.color)) },
        showTotals = showValues,
        height = height,
        modifier = modifier,
    )
}

/**
 * One stacked column per category (e.g. a month), one colored layer per
 * series (e.g. a tag). Pair it with a [ChartLegend].
 */
@Composable
fun StackedBarChart(
    categories: List<String>,
    series: List<StackedSeries>,
    modifier: Modifier = Modifier,
    height: Dp = 240.dp,
    showTotals: Boolean = true,
) {
    val stacks = categories.indices.map { index ->
        series.map { layer -> BarSegment(layer.values.getOrElse(index) { 0.0 }, layer.color) }
    }
    ColumnChart(
        categories = categories,
        stacks = stacks,
        showTotals = showTotals,
        height = height,
        modifier = modifier,
    )
}

@Composable
private fun ColumnChart(
    categories: List<String>,
    stacks: List<List<BarSegment>>,
    showTotals: Boolean,
    height: Dp,
    modifier: Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val style = rememberChartStyle()

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
    ) {
        if (categories.isEmpty()) return@Canvas

        val totals = stacks.map { segments -> segments.sumOf { it.value.coerceAtLeast(0.0) } }
        val ticks = ChartMath.niceTicks(totals.maxOrNull() ?: 0.0)
        val top = ticks.last()

        // ---- Margins (measured from the labels) --------------------------------
        val yLabels = ticks.map { textMeasurer.measure(ChartMath.formatNumber(it), style = style.text) }
        val leftPad = (yLabels.maxOfOrNull { it.size.width } ?: 0) + 6.dp.toPx()
        val rightPad = 4.dp.toPx()
        val textHeight = yLabels.first().size.height.toFloat()
        val topPad = (if (showTotals) textHeight else textHeight / 2f) + 4.dp.toPx()
        val plotWidth = size.width - leftPad - rightPad
        if (plotWidth <= 0f) return@Canvas
        val slot = plotWidth / categories.size

        val xLabels = categories.map { label ->
            measureLabel(textMeasurer, label, style.text, MaxRotatedLabelWidth.toPx().toInt())
        }
        val labelHeight = xLabels.maxOf { it.size.height }.toFloat()
        val widestLabel = xLabels.maxOf { it.size.width }.toFloat()
        val rotateLabels = widestLabel > slot * 0.9f
        val bottomPad = if (rotateLabels) {
            (widestLabel + labelHeight) * DIAGONAL + 8.dp.toPx()
        } else {
            labelHeight + 8.dp.toPx()
        }

        val plotTop = topPad
        val plotBottom = size.height - bottomPad
        if (plotBottom <= plotTop) return@Canvas
        fun yFor(value: Double): Float = plotBottom - ((value / top) * (plotBottom - plotTop)).toFloat()

        // ---- Gridlines + y tick labels --------------------------------------------
        ticks.forEachIndexed { index, tick ->
            val y = yFor(tick)
            drawLine(style.grid, Offset(leftPad, y), Offset(size.width - rightPad, y), strokeWidth = 1.dp.toPx())
            val layout = yLabels[index]
            drawText(layout, topLeft = Offset(leftPad - 4.dp.toPx() - layout.size.width, y - layout.size.height / 2f))
        }

        // ---- Bars ----------------------------------------------------------------------
        val barWidth = slot * 0.7f
        stacks.forEachIndexed { index, segments ->
            val left = leftPad + slot * index + (slot - barWidth) / 2f
            var base = 0.0
            segments.forEach { segment ->
                val value = segment.value
                if (value > 0.0) {
                    val yTop = yFor(base + value)
                    val yBottom = yFor(base)
                    drawRect(color = segment.color, topLeft = Offset(left, yTop), size = Size(barWidth, yBottom - yTop))
                    base += value
                }
            }
            val total = totals[index]
            if (showTotals && total > 0.0) {
                val layout = textMeasurer.measure(ChartMath.formatNumber(total), style = style.text)
                if (layout.size.width <= slot) {
                    drawText(
                        layout,
                        topLeft = Offset(
                            left + barWidth / 2f - layout.size.width / 2f,
                            yFor(total) - layout.size.height - 2.dp.toPx(),
                        ),
                    )
                }
            }
        }

        // ---- Axis + category labels ---------------------------------------------------
        drawLine(style.axis, Offset(leftPad, plotBottom), Offset(size.width - rightPad, plotBottom), strokeWidth = 1.dp.toPx())
        val stride = if (rotateLabels) ChartMath.labelStride(slot, labelHeight * 1.2f) else 1
        xLabels.forEachIndexed { index, layout ->
            if (index % stride != 0) return@forEachIndexed
            val centerX = leftPad + slot * index + slot / 2f
            if (rotateLabels) {
                // The label's right end sits under its bar; the text runs down-left at 45°.
                val anchor = Offset(centerX, plotBottom + 6.dp.toPx())
                rotate(degrees = -45f, pivot = anchor) {
                    drawText(layout, topLeft = Offset(anchor.x - layout.size.width, anchor.y - layout.size.height / 2f))
                }
            } else {
                drawText(layout, topLeft = Offset(centerX - layout.size.width / 2f, plotBottom + 4.dp.toPx()))
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Horizontal bars
// -----------------------------------------------------------------------------

/**
 * Bars lying down: label on the left (cut with "…" past 40% of the width),
 * bar, then the value. Ticks along the bottom.
 */
@Composable
fun HorizontalBarChart(
    bars: List<ChartBar>,
    modifier: Modifier = Modifier,
    rowHeight: Dp = 30.dp,
) {
    val textMeasurer = rememberTextMeasurer()
    val style = rememberChartStyle()
    val axisHeight = 24.dp

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(rowHeight * bars.size.coerceAtLeast(1) + axisHeight),
    ) {
        if (bars.isEmpty()) return@Canvas

        val ticks = ChartMath.niceTicks(bars.maxOf { it.value })
        val top = ticks.last()

        val labels = bars.map { bar -> measureLabel(textMeasurer, bar.label, style.text, (size.width * 0.4f).toInt()) }
        val values = bars.map { bar -> textMeasurer.measure(ChartMath.formatNumber(bar.value), style = style.text) }
        val tickLabels = ticks.map { textMeasurer.measure(ChartMath.formatNumber(it), style = style.text) }

        val plotLeft = labels.maxOf { it.size.width } + 8.dp.toPx()
        // Room after the longest bar for its value, and for the last tick label to fit.
        val plotRight = size.width - maxOf(
            values.maxOf { it.size.width } + 6.dp.toPx(),
            tickLabels.last().size.width / 2f + 2.dp.toPx(),
        )
        val plotBottom = size.height - axisHeight.toPx()
        if (plotRight <= plotLeft || plotBottom <= 0f) return@Canvas
        val rowPx = plotBottom / bars.size
        fun xFor(value: Double): Float = plotLeft + ((value / top) * (plotRight - plotLeft)).toFloat()

        // ---- Vertical gridlines + tick labels ------------------------------------------
        ticks.forEachIndexed { index, tick ->
            val x = xFor(tick)
            drawLine(style.grid, Offset(x, 0f), Offset(x, plotBottom), strokeWidth = 1.dp.toPx())
            val layout = tickLabels[index]
            drawText(layout, topLeft = Offset(x - layout.size.width / 2f, plotBottom + 4.dp.toPx()))
        }

        // ---- Bars ------------------------------------------------------------------------------
        val barHeight = rowPx * 0.62f
        bars.forEachIndexed { index, bar ->
            val centerY = rowPx * index + rowPx / 2f
            val label = labels[index]
            drawText(label, topLeft = Offset(plotLeft - 8.dp.toPx() - label.size.width, centerY - label.size.height / 2f))
            val end = xFor(bar.value.coerceAtLeast(0.0))
            drawRect(
                color = bar.color,
                topLeft = Offset(plotLeft, centerY - barHeight / 2f),
                size = Size((end - plotLeft).coerceAtLeast(0f), barHeight),
            )
            val value = values[index]
            drawText(value, topLeft = Offset(end + 4.dp.toPx(), centerY - value.size.height / 2f))
        }

        drawLine(style.axis, Offset(plotLeft, 0f), Offset(plotLeft, plotBottom), strokeWidth = 1.dp.toPx())
    }
}

// -----------------------------------------------------------------------------
// Scatter
// -----------------------------------------------------------------------------

/**
 * Dots in colored series on two numeric axes, plus an optional dashed line
 * (e.g. an OLS trendline). Dots are slightly see-through so overlaps show.
 */
@Composable
fun ScatterChart(
    series: List<ScatterSeries>,
    xAxis: ChartAxis,
    yAxis: ChartAxis,
    modifier: Modifier = Modifier,
    line: ChartLine? = null,
    height: Dp = 240.dp,
) {
    val textMeasurer = rememberTextMeasurer()
    val style = rememberChartStyle()

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
    ) {
        val yLabels = yAxis.ticks.map { textMeasurer.measure(yAxis.label(it), style = style.text) }
        val xLabels = xAxis.ticks.map { textMeasurer.measure(xAxis.label(it), style = style.text) }

        val textHeight = (yLabels.firstOrNull() ?: xLabels.firstOrNull())?.size?.height?.toFloat() ?: 0f
        val plotLeft = (yLabels.maxOfOrNull { it.size.width } ?: 0) + 6.dp.toPx()
        val plotRight = size.width - ((xLabels.lastOrNull()?.size?.width ?: 0) / 2f).coerceAtLeast(8.dp.toPx())
        val plotTop = textHeight / 2f + 4.dp.toPx()
        val plotBottom = size.height - textHeight - 8.dp.toPx()
        if (plotRight <= plotLeft || plotBottom <= plotTop) return@Canvas

        val xSpan = (xAxis.max - xAxis.min).takeIf { it > 0.0 } ?: 1.0
        val ySpan = (yAxis.max - yAxis.min).takeIf { it > 0.0 } ?: 1.0
        fun px(x: Double): Float = plotLeft + (((x - xAxis.min) / xSpan) * (plotRight - plotLeft)).toFloat()
        fun py(y: Double): Float = plotBottom - (((y - yAxis.min) / ySpan) * (plotBottom - plotTop)).toFloat()

        // ---- Grid + tick labels -----------------------------------------------------------
        yAxis.ticks.forEachIndexed { index, tick ->
            val y = py(tick)
            drawLine(style.grid, Offset(plotLeft, y), Offset(plotRight, y), strokeWidth = 1.dp.toPx())
            val layout = yLabels[index]
            drawText(layout, topLeft = Offset(plotLeft - 4.dp.toPx() - layout.size.width, y - layout.size.height / 2f))
        }
        xAxis.ticks.forEachIndexed { index, tick ->
            val x = px(tick)
            drawLine(style.grid.copy(alpha = 0.5f), Offset(x, plotTop), Offset(x, plotBottom), strokeWidth = 1.dp.toPx())
            val layout = xLabels[index]
            drawText(layout, topLeft = Offset(x - layout.size.width / 2f, plotBottom + 4.dp.toPx()))
        }
        drawLine(style.axis, Offset(plotLeft, plotBottom), Offset(plotRight, plotBottom), strokeWidth = 1.dp.toPx())
        drawLine(style.axis, Offset(plotLeft, plotTop), Offset(plotLeft, plotBottom), strokeWidth = 1.dp.toPx())

        // ---- Dots + line (clipped to the plot area) ---------------------------------------------
        clipRect(left = plotLeft, top = plotTop - 12.dp.toPx(), right = plotRight + 12.dp.toPx(), bottom = plotBottom) {
            series.forEach { s ->
                s.points.forEach { point ->
                    val center = Offset(px(point.x), py(point.y))
                    val radius = point.radius.toPx()
                    drawCircle(color = s.color.copy(alpha = 0.78f), radius = radius, center = center)
                    drawCircle(color = style.dotOutline, radius = radius, center = center, style = Stroke(width = 1.dp.toPx()))
                }
            }
            if (line != null) {
                drawLine(
                    color = line.color,
                    start = Offset(px(line.startX), py(line.startY)),
                    end = Offset(px(line.endX), py(line.endY)),
                    strokeWidth = 2.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f),
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Legend
// -----------------------------------------------------------------------------

/** A wrapping row of "● Label" items to put under a chart. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChartLegend(entries: List<LegendEntry>, modifier: Modifier = Modifier) {
    if (entries.isEmpty()) return
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        entries.forEach { entry ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (entry.isLine) {
                    Box(
                        modifier = Modifier
                            .width(14.dp)
                            .height(3.dp)
                            .background(entry.color),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(entry.color, CircleShape),
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    text = entry.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 180.dp),
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Helpers
// -----------------------------------------------------------------------------

/** Lays out a one-line label no wider than [maxWidthPx] (cut with "…"). */
private fun DrawScope.measureLabel(
    textMeasurer: TextMeasurer,
    text: String,
    style: TextStyle,
    maxWidthPx: Int,
): TextLayoutResult = textMeasurer.measure(
    text = text,
    style = style,
    overflow = TextOverflow.Ellipsis,
    softWrap = false,
    maxLines = 1,
    constraints = Constraints(maxWidth = maxWidthPx.coerceAtLeast(1)),
    density = this,
)
