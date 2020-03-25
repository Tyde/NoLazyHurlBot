<#macro runTable runList redacted>
    <table class="table">
        <thead>
        <tr>
            <td scope="col">Name</td>
            <td scope="col">Total km</td>
            <td scope="col">Number of Runs</td>
            <td scope="col">Average km per run</td>
        </tr>
        </thead>
        <#list runList as userRun>
            <tr>
                <td><a data-toggle="collapse" href="#collapse${userRun.user.id}"
                       role="button" aria-expanded="false" aria-controls="collapse${userRun.user.id}">
                        <#if redacted>
                            Redacted (privacy)
                        <#else>
                            ${userRun.user.bestName}
                        </#if>
                    </a></td>
                <td>${userRun.totalKM}</td>
                <td>${userRun.totalRuns}</td>
                <td>${userRun.getAverage()}</td>
            </tr>
            <tr class="collapse" id="collapse${userRun.user.id}">
                <td colspan="4">
                    <table>
                        <#list userRun.listRuns as run>
                            <tr>
                                <td>${run.formatTime()}</td>
                                <td>${run.length}</td>
                            </tr>
                        </#list>
                    </table>
                </td>
            </tr>
        </#list>
    </table>
</#macro>