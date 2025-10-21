package com.EcoChartPro.plugins.community;

import com.EcoChartPro.api.indicator.ApiKLine;
import com.EcoChartPro.api.indicator.CustomIndicator;
import com.EcoChartPro.api.indicator.IndicatorType;
import com.EcoChartPro.api.indicator.Parameter;
import com.EcoChartPro.api.indicator.ParameterType;
import com.EcoChartPro.api.indicator.drawing.DataPoint;
import com.EcoChartPro.api.indicator.drawing.DrawableBox;
import com.EcoChartPro.api.indicator.drawing.DrawableLine;
import com.EcoChartPro.api.indicator.drawing.DrawableObject;
import com.EcoChartPro.api.indicator.drawing.DrawablePolyline;
import com.EcoChartPro.core.indicator.IndicatorContext;

import java.awt.Color;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A stateful implementation of the Relative Strength Index (RSI).
 * This indicator is drawn in a separate pane and shows momentum.
 * It uses Wilder's Smoothing for its calculations, which is a stateful/recursive process.
 * Version: 1.1.0 (State Management Fix)
 */
public class StatefulRsiIndicator implements CustomIndicator {

    private static final int CALCULATION_SCALE = 10;
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    @Override
    public String getName() {
        return "Stateful RSI";
    }

    @Override
    public IndicatorType getType() {
        return IndicatorType.PANE;
    }

    @Override
    public List<Parameter> getParameters() {
        return List.of(
            new Parameter("Period", ParameterType.INTEGER, 14),
            new Parameter("Overbought", ParameterType.INTEGER, 70),
            new Parameter("Oversold", ParameterType.INTEGER, 30),
            new Parameter("RSI Color", ParameterType.COLOR, new Color(156, 39, 176)), // Purple
            new Parameter("Band Color", ParameterType.COLOR, new Color(128, 128, 128, 50)) // Semi-transparent gray
        );
    }

    @Override
    public void onSettingsChanged(Map<String, Object> newSettings, Map<String, Object> state) {
        // Critical: If the period changes, all previous state is invalid.
        // Clearing the state map signals the IndicatorRunner to set isReset=true on the next run.
        state.clear();
    }

    @Override
    public List<DrawableObject> calculate(IndicatorContext context) {
        int period = (int) context.settings().get("Period");
        Color color = (Color) context.settings().get("RSI Color");
        BigDecimal overboughtLevel = BigDecimal.valueOf((Integer) context.settings().get("Overbought"));
        BigDecimal oversoldLevel = BigDecimal.valueOf((Integer) context.settings().get("Oversold"));
        Color bandColor = (Color) context.settings().get("Band Color");
        
        List<ApiKLine> klineData = context.klineData();
        Map<String, Object> state = context.state();

        if (klineData.size() < period) {
            return Collections.emptyList();
        }

        List<DataPoint> rsiPoints = new ArrayList<>();
        BigDecimal periodDecimal = BigDecimal.valueOf(period);

        BigDecimal avgGain;
        BigDecimal avgLoss;

        int startIndex = 1; // Start calculations from the second bar

        // [FIX] Rely solely on context.isReset() to manage state initialization
        if (context.isReset() || state.get("avgGain") == null) {
            // State is invalid or this is the first run. We must seed the initial values.
            BigDecimal firstGainSum = BigDecimal.ZERO;
            BigDecimal firstLossSum = BigDecimal.ZERO;
            
            // Calculate the simple average for the first 'period' changes.
            for (int i = 1; i <= period; i++) {
                BigDecimal change = klineData.get(i).close().subtract(klineData.get(i - 1).close());
                firstGainSum = firstGainSum.add(change.signum() > 0 ? change : BigDecimal.ZERO);
                firstLossSum = firstLossSum.add(change.signum() < 0 ? change.abs() : BigDecimal.ZERO);
            }
            avgGain = firstGainSum.divide(periodDecimal, CALCULATION_SCALE, RoundingMode.HALF_UP);
            avgLoss = firstLossSum.divide(periodDecimal, CALCULATION_SCALE, RoundingMode.HALF_UP);
            
            // The first RSI point corresponds to the candle at index `period`.
            startIndex = period;
        } else {
            // State is valid. Retrieve the last known values.
            avgGain = (BigDecimal) state.get("avgGain");
            avgLoss = (BigDecimal) state.get("avgLoss");
            
            // Find the starting point for incremental calculation.
            // This is crucial to prevent re-calculating the entire history.
            Instant lastTimestamp = (Instant) state.get("lastTimestamp");
            for (int i = 1; i < klineData.size(); i++) {
                if (klineData.get(i).timestamp().isAfter(lastTimestamp)) {
                    startIndex = i;
                    break;
                }
            }
        }

        // --- Core Calculation Loop ---
        for (int i = startIndex; i < klineData.size(); i++) {
            BigDecimal change = klineData.get(i).close().subtract(klineData.get(i-1).close());
            BigDecimal gain = change.signum() > 0 ? change : BigDecimal.ZERO;
            BigDecimal loss = change.signum() < 0 ? change.abs() : BigDecimal.ZERO;

            // Apply Wilder's Smoothing (a recursive formula).
            // AvgGain = ((PrevAvgGain * (period - 1)) + CurrentGain) / period
            avgGain = avgGain.multiply(periodDecimal.subtract(BigDecimal.ONE)).add(gain).divide(periodDecimal, CALCULATION_SCALE, RoundingMode.HALF_UP);
            avgLoss = avgLoss.multiply(periodDecimal.subtract(BigDecimal.ONE)).add(loss).divide(periodDecimal, CALCULATION_SCALE, RoundingMode.HALF_UP);

            BigDecimal rsi;
            if (avgLoss.signum() == 0) {
                rsi = ONE_HUNDRED;
            } else {
                BigDecimal rs = avgGain.divide(avgLoss, CALCULATION_SCALE, RoundingMode.HALF_UP);
                rsi = ONE_HUNDRED.subtract(ONE_HUNDRED.divide(BigDecimal.ONE.add(rs), CALCULATION_SCALE, RoundingMode.HALF_UP));
            }
            
            rsiPoints.add(new DataPoint(klineData.get(i).timestamp(), rsi));
        }
        
        // --- Store the final state for the next calculate() call ---
        if (!klineData.isEmpty()) {
            state.put("avgGain", avgGain);
            state.put("avgLoss", avgLoss);
            state.put("lastTimestamp", klineData.get(klineData.size() - 1).timestamp());
        }

        // --- Prepare Drawable Objects ---
        List<DrawableObject> drawables = new ArrayList<>();
        
        if (!rsiPoints.isEmpty()) {
            drawables.add(new DrawablePolyline(rsiPoints, color, 2.0f));
        }

        if (!klineData.isEmpty()) {
            DataPoint corner1 = new DataPoint(klineData.get(0).timestamp(), overboughtLevel);
            DataPoint corner2 = new DataPoint(klineData.get(klineData.size() - 1).timestamp(), oversoldLevel);
            drawables.add(new DrawableBox(corner1, corner2, bandColor, null, 0f));
        }
        
        // Add horizontal lines for overbought and oversold
        drawables.add(new DrawableLine.Horizontal(overboughtLevel, bandColor.darker(), 1.0f, true));
        drawables.add(new DrawableLine.Horizontal(oversoldLevel, bandColor.darker(), 1.0f, true));

        return drawables;
    }
}
