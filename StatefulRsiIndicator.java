package com.EcoChartPro.plugins.community;

import com.EcoChartPro.api.indicator.CustomIndicator;
import com.EcoChartPro.api.indicator.IndicatorType;
import com.EcoChartPro.api.indicator.Parameter;
import com.EcoChartPro.api.indicator.ParameterType;
import com.EcoChartPro.api.indicator.drawing.DataPoint;
import com.EcoChartPro.api.indicator.drawing.DrawableLine;
import com.EcoChartPro.api.indicator.drawing.DrawableObject;
import com.EcoChartPro.api.indicator.drawing.DrawablePolyline;
import com.EcoChartPro.core.indicator.IndicatorContext;
import com.EcoChartPro.api.indicator.ApiKLine;

import java.awt.Color;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A stateful, performance-optimized implementation of the Relative Strength Index (RSI).
 * It maintains the running average of gains and losses between calculations,
 * making it efficient for real-time updates and large datasets.
 */
public class StatefulRsiIndicator implements CustomIndicator {

    // State variables to hold running averages between calls
    private BigDecimal avgGain = BigDecimal.ZERO;
    private BigDecimal avgLoss = BigDecimal.ZERO;
    private int period;
    private int processedDataSize = 0;

    @Override
    public String getName() {
        return "Stateful RSI";
    }

    @Override
    public IndicatorType getType() {
        return IndicatorType.PANE; // Renders in a separate pane below the main chart
    }

    @Override
    public List<Parameter> getParameters() {
        return List.of(
            new Parameter("Period", ParameterType.INTEGER, 14),
            new Parameter("Overbought", ParameterType.DECIMAL, BigDecimal.valueOf(70)),
            new Parameter("Oversold", ParameterType.DECIMAL, BigDecimal.valueOf(30)),
            new Parameter("RSI Color", ParameterType.COLOR, new Color(0x61AFEF)),
            new Parameter("OB/OS Color", ParameterType.COLOR, new Color(0xE06C75))
        );
    }
    
    /**
     * [NEW] Implements the lifecycle hook to reset state when settings change.
     * This is crucial for a stateful indicator.
     */
    @Override
    public void onSettingsChanged(Map<String, Object> newSettings, Map<String, Object> state) {
        // Since settings are changing, we must reset our calculations.
        this.avgGain = BigDecimal.ZERO;
        this.avgLoss = BigDecimal.ZERO;
        this.processedDataSize = 0;
    }

    @Override
    public List<DrawableObject> calculate(IndicatorContext context) {
        List<ApiKLine> data = context.klineData();
        Map<String, Object> settings = context.settings();
        this.period = (int) settings.get("Period");

        // [MODIFIED] Use the context's reset flag, which is more reliable.
        if (context.isReset()) {
            avgGain = BigDecimal.ZERO;
            avgLoss = BigDecimal.ZERO;
            processedDataSize = 0;
        }

        if (data.size() < period) {
            return new ArrayList<>(); // Not enough data to calculate
        }

        List<DrawableObject> drawables = new ArrayList<>();
        // [MODIFIED] Changed from DrawableLine.Point to the correct DataPoint record.
        List<DataPoint> rsiPoints = new ArrayList<>();

        // If starting fresh, calculate the initial SMA for average gain/loss
        if (processedDataSize == 0) {
            BigDecimal totalGain = BigDecimal.ZERO;
            BigDecimal totalLoss = BigDecimal.ZERO;

            for (int i = 1; i < period; i++) {
                BigDecimal change = data.get(i).close().subtract(data.get(i - 1).close());
                if (change.compareTo(BigDecimal.ZERO) > 0) {
                    totalGain = totalGain.add(change);
                } else {
                    totalLoss = totalLoss.add(change.abs());
                }
            }
            avgGain = totalGain.divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);
            avgLoss = totalLoss.divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);
            processedDataSize = period -1;
        }

        // Calculate RSI for new data points using Wilder's smoothing (RMA)
        for (int i = processedDataSize; i < data.size(); i++) {
            if (i == 0) continue;

            BigDecimal change = data.get(i).close().subtract(data.get(i - 1).close());
            BigDecimal gain = change.compareTo(BigDecimal.ZERO) > 0 ? change : BigDecimal.ZERO;
            BigDecimal loss = change.compareTo(BigDecimal.ZERO) < 0 ? change.abs() : BigDecimal.ZERO;

            // Apply Wilder's smoothing
            avgGain = (avgGain.multiply(BigDecimal.valueOf(period - 1)).add(gain)).divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);
            avgLoss = (avgLoss.multiply(BigDecimal.valueOf(period - 1)).add(loss)).divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);

            if (avgLoss.equals(BigDecimal.ZERO)) {
                // [MODIFIED] Use DataPoint
                rsiPoints.add(new DataPoint(data.get(i).timestamp(), BigDecimal.valueOf(100)));
                continue;
            }

            BigDecimal rs = avgGain.divide(avgLoss, 8, RoundingMode.HALF_UP);
            BigDecimal rsi = BigDecimal.valueOf(100).subtract(BigDecimal.valueOf(100).divide(BigDecimal.ONE.add(rs), 8, RoundingMode.HALF_UP));
            // [MODIFIED] Use DataPoint
            rsiPoints.add(new DataPoint(data.get(i).timestamp(), rsi));
        }

        processedDataSize = data.size();

        // Add the lines to be drawn
        // [MODIFIED] Create a DrawablePolyline from the list of points
        drawables.add(new DrawablePolyline(rsiPoints, (Color) settings.get("RSI Color"), 1.5f));
        // [MODIFIED] Use the new DrawableLine.Horizontal record
        drawables.add(new DrawableLine.Horizontal((BigDecimal) settings.get("Overbought"), (Color) settings.get("OB/OS Color"), 1.0f, true));
        drawables.add(new DrawableLine.Horizontal((BigDecimal) settings.get("Oversold"), (Color) settings.get("OB/OS Color"), 1.0f, true));

        return drawables;
    }
}
