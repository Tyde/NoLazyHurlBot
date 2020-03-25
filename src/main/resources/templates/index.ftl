<#-- @ftlvariable name="userRuns" type="java.util.List<de.darmstadtgaa.UserRuns>" -->
<#-- @ftlvariable name="userBikeRuns" type="java.util.List<de.darmstadtgaa.UserRuns>" -->
<#-- @ftlvariable name="redacted" type="boolean" -->
<#-- @ftlvariable name="chartDataTotal" type="String" -->
<#-- @ftlvariable name="chartDataRuns" type="String" -->
<#-- @ftlvariable name="chartDataBike" type="String" -->
<#-- @ftlvariable name="totalKM" type="Double" -->
<#-- @ftlvariable name="runKM" type="Double" -->
<#-- @ftlvariable name="bikeKM" type="Double" -->
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <title>#NoLazyHurl by Darmstadt GAA</title>
    <link rel="stylesheet" href="https://stackpath.bootstrapcdn.com/bootstrap/4.4.1/css/bootstrap.min.css" integrity="sha384-Vkoo8x4CGsO3+Hhxv8T/Q5PaXtkKtu6ug5TOeNV6gBiFeWPGFN9MuhOf23Q9Ifjh" crossorigin="anonymous">
    <script src="https://ajax.aspnetcdn.com/ajax/jQuery/jquery-3.4.1.slim.js"></script>
    <script src="https://cdn.jsdelivr.net/npm/popper.js@1.16.0/dist/umd/popper.min.js" integrity="sha384-Q6E9RHvbIyZFJoft+2mJbHaEWldlvI9IOYy5n3zV9zzTtmI3UksdQRVvoxMfooAo" crossorigin="anonymous"></script>
    <script src="https://stackpath.bootstrapcdn.com/bootstrap/4.4.1/js/bootstrap.min.js" integrity="sha384-wfSDF2E50Y2D1uUdj0O3uMBJnjuUD4Ih7YwaYd1iqfktj0Uod8GCExl3Og8ifwB6" crossorigin="anonymous"></script>
    <script src="https://cdn.jsdelivr.net/npm/echarts@4.7.0/dist/echarts.js"></script>
</head>
<body>

<div class="container">
    <p class="h3 mt-3" >Since 14th of March Darmstadt GAA has left ${totalKM?string[",##0.00"]} km behind.
        While ${runKM?string[",##0.00"]} km have been run, ${bikeKM?string[",##0.00"]} km have been on bike.</p>

    <div id="timechart" class="mt-3" style="width: 768px;height:300px;margin: auto;"></div>
    <script type="application/javascript">
        var timechart = echarts.init(document.getElementById('timechart'))

        const options = {
            year: 'numeric', month: 'numeric', day: 'numeric',
            hour: 'numeric', minute: 'numeric',
            hour12: false}
        const dtf = new Intl.DateTimeFormat('de-DE', options)
        var option = {
            title: {
                text: 'Kilometer over time'
            },
            tooltip: {
                trigger: 'axis',
                formatter: function(params,ticket,callback) {
                    var date = new Date(params[0].data[0])
                    var output = dtf.format(date)+":<br>"
                    params.forEach(function(series) {
                        output += series.seriesName+": " + series.data[1]+" km<br>"
                    })
                    return output
                }
            },
            legend: {
                data: ['Total','Running', 'Biking']
            },
            xAxis: {
                type:'time'
            },
            yAxis: {},
            series: [{
                name: 'Total',
                type: 'line',
                step: 'end',
                data: ${chartDataTotal}
            },{
                name: 'Running',
                type: 'line',
                step: 'end',
                data: ${chartDataRuns}
            },{
                name: 'Biking',
                type: 'line',
                step: 'end',
                data: ${chartDataBike}
            }]
        }
        timechart.setOption(option)
    </script>

    <#import "table.ftl" as table>

    <h2>Running</h2>
    <@table.runTable runList=userRuns redacted=redacted/>
    <h2>Biking</h2>
    <@table.runTable runList=userBikeRuns redacted=redacted/>


</div>
</body>
</html>