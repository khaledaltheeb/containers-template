using System.Data;
using System.Reflection;
using Ari.Core.Domain;
using OxyPlot;
using OxyPlot.Series;

namespace Ari.Desktop.Services;

public static class ReleaseVisualSelfTest
{
    public static void Run()
    {
        var cells = new List<AnalysisCell>();
        var rows = new[] { "F-16-101", "F-16-102", "F-16-103", "F-16-104", "F-16-105", "F-16-106" };
        var faults = new[] { "HYD", "ELEC", "FUEL", "AVIONICS", "ECS" };
        for (var r = 0; r < rows.Length; r++)
        for (var c = 0; c < faults.Length; c++)
            cells.Add(new AnalysisCell { RowKey = rows[r], ColumnKey = faults[c], Value = (r + 2L) * (c + 3L) + (r * c) });

        var buildPlot = typeof(AnalysisPresentationService).GetMethod("BuildPlot", BindingFlags.NonPublic | BindingFlags.Static)
            ?? throw new InvalidOperationException("BuildPlot was not found.");
        var buildTable = typeof(AnalysisPresentationService).GetMethod("BuildTable", BindingFlags.NonPublic | BindingFlags.Static)
            ?? throw new InvalidOperationException("BuildTable was not found.");

        VerifyPlot(buildPlot, cells, AnalysisVisualizationKind.Columns, typeof(ColumnSeries));
        VerifyPlot(buildPlot, cells, AnalysisVisualizationKind.Line, typeof(LineSeries));
        VerifyPlot(buildPlot, cells, AnalysisVisualizationKind.Heatmap, typeof(HeatMapSeries));

        var table = (DataTable?)buildTable.Invoke(null, new object?[] { cells, true })
            ?? throw new InvalidOperationException("Analysis table renderer returned null.");
        if (table.Rows.Count != rows.Length || table.Columns.Count != faults.Length + 1)
            throw new InvalidOperationException($"Unexpected analysis table shape: {table.Rows.Count}x{table.Columns.Count}.");

        var trend = new TrendComparisonResult
        {
            SeriesDimension = AnalysisDimension.Aircraft,
            Periods = new[] { "2026-01", "2026-02", "2026-03", "2026-04", "2026-05", "2026-06" },
            Points = new[]
            {
                new TrendSeriesPoint { Period="2026-01", SeriesKey="F-16-101", Count=10, MovingAverage=10 },
                new TrendSeriesPoint { Period="2026-02", SeriesKey="F-16-101", Count=14, MovingAverage=12 },
                new TrendSeriesPoint { Period="2026-03", SeriesKey="F-16-101", Count=18, MovingAverage=14 },
                new TrendSeriesPoint { Period="2026-04", SeriesKey="F-16-101", Count=15, MovingAverage=15.67m },
                new TrendSeriesPoint { Period="2026-05", SeriesKey="F-16-101", Count=20, MovingAverage=17.67m },
                new TrendSeriesPoint { Period="2026-06", SeriesKey="F-16-101", Count=25, MovingAverage=20 },
                new TrendSeriesPoint { Period="2026-01", SeriesKey="F-16-102", Count=8, MovingAverage=8 },
                new TrendSeriesPoint { Period="2026-02", SeriesKey="F-16-102", Count=9, MovingAverage=8.5m },
                new TrendSeriesPoint { Period="2026-03", SeriesKey="F-16-102", Count=13, MovingAverage=10 },
                new TrendSeriesPoint { Period="2026-04", SeriesKey="F-16-102", Count=11, MovingAverage=11 },
                new TrendSeriesPoint { Period="2026-05", SeriesKey="F-16-102", Count=16, MovingAverage=13.33m },
                new TrendSeriesPoint { Period="2026-06", SeriesKey="F-16-102", Count=19, MovingAverage=15.33m }
            },
            Summaries = new[]
            {
                new TrendSeriesSummary { SeriesKey="F-16-101", TotalCount=102, PreviousCount=20, LatestCount=25, LatestChangePercentage=25, PeakCount=25, PeakPeriod="2026-06", SlopePerPeriod=2.5m },
                new TrendSeriesSummary { SeriesKey="F-16-102", TotalCount=76, PreviousCount=16, LatestCount=19, LatestChangePercentage=18.75m, PeakCount=19, PeakPeriod="2026-06", SlopePerPeriod=2m }
            },
            Overall = new[]
            {
                new TrendPoint { Period="2026-01", Count=18, MovingAverage=18 },
                new TrendPoint { Period="2026-02", Count=23, MovingAverage=20.5m },
                new TrendPoint { Period="2026-03", Count=31, MovingAverage=24 },
                new TrendPoint { Period="2026-04", Count=26, MovingAverage=26.67m },
                new TrendPoint { Period="2026-05", Count=36, MovingAverage=31m },
                new TrendPoint { Period="2026-06", Count=44, MovingAverage=35.33m }
            }
        };
        var trendPlot = AnalysisPresentationService.BuildTrendComparisonPlot(trend, 3, "Synthetic aircraft trend", true);
        if (trendPlot.Series.Count < 2) throw new InvalidOperationException("Trend renderer did not create multiple series.");
        VerifyPng(trendPlot, "trend");

        Console.WriteLine("Visual smoke: columns, line, heatmap, table, multi-series trend PNG = PASS");
    }

    private static void VerifyPlot(MethodInfo buildPlot, IReadOnlyList<AnalysisCell> cells, AnalysisVisualizationKind kind, Type expectedSeriesType)
    {
        var model = (PlotModel?)buildPlot.Invoke(null, new object?[] { cells, kind, true })
            ?? throw new InvalidOperationException($"{kind} plot renderer returned null.");
        if (model.Series.Count == 0 || !model.Series.Any(expectedSeriesType.IsInstanceOfType))
            throw new InvalidOperationException($"{kind} plot did not contain {expectedSeriesType.Name}.");
        VerifyPng(model, kind.ToString());
    }

    private static void VerifyPng(PlotModel plot, string name)
    {
        var dataUri = AnalysisPresentationService.PlotToDataUri(plot, 1100, 620);
        const string prefix = "data:image/png;base64,";
        if (!dataUri.StartsWith(prefix, StringComparison.Ordinal))
            throw new InvalidOperationException($"{name} renderer did not produce a PNG data URI.");
        var bytes = Convert.FromBase64String(dataUri[prefix.Length..]);
        if (bytes.Length < 5_000 || bytes[0] != 0x89 || bytes[1] != 0x50 || bytes[2] != 0x4E || bytes[3] != 0x47)
            throw new InvalidOperationException($"{name} PNG output is invalid or unexpectedly small ({bytes.Length} bytes).");
    }
}
