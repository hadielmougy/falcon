package io.loadstorm.web.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.loadstorm.api.pool.PoolMetricsSnapshot;
import io.loadstorm.web.environment.WebEnvironment;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Embedded Tomcat server that provides:
 * - Dashboard HTML page
 * - SSE endpoint streaming metrics every second
 * - REST API for starting/stopping tests and updating config
 */
public class LoadStormWebServer {

    private static final Logger log = LoggerFactory.getLogger(LoadStormWebServer.class);

    private final WebEnvironment environment;
    private final int port;
    private final ObjectMapper objectMapper;
    private final List<AsyncContext> sseClients = new CopyOnWriteArrayList<>();
    private Tomcat tomcat;

    public LoadStormWebServer(WebEnvironment environment, int port) {
        this.environment = environment;
        this.port = port;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public void start() {
        try {
            tomcat = new Tomcat();
            tomcat.setPort(port);
            tomcat.getConnector(); // trigger connector creation

            Context ctx = tomcat.addContext("", null);

            // SSE endpoint for real-time metrics
            Tomcat.addServlet(ctx, "sse", new SseServlet()).setAsyncSupported(true);
            ctx.addServletMappingDecoded("/api/metrics/stream", "sse");

            // REST: start test
            Tomcat.addServlet(ctx, "start", new StartTestServlet());
            ctx.addServletMappingDecoded("/api/test/start", "start");

            // REST: stop test
            Tomcat.addServlet(ctx, "stop", new StopTestServlet());
            ctx.addServletMappingDecoded("/api/test/stop", "stop");

            // REST: get config
            Tomcat.addServlet(ctx, "config", new ConfigServlet());
            ctx.addServletMappingDecoded("/api/config", "config");

            // REST: update config
            Tomcat.addServlet(ctx, "updateConfig", new UpdateConfigServlet());
            ctx.addServletMappingDecoded("/api/config/update", "updateConfig");

            // REST: current metrics snapshot
            Tomcat.addServlet(ctx, "snapshot", new SnapshotServlet());
            ctx.addServletMappingDecoded("/api/metrics/snapshot", "snapshot");

            // Dashboard at root
            Tomcat.addServlet(ctx, "dashboard", new DashboardServlet());
            ctx.addServletMappingDecoded("/", "dashboard");

            tomcat.start();
            log.info("LoadStorm web server started on port {}", port);

        } catch (Exception e) {
            throw new RuntimeException("Failed to start web server", e);
        }
    }

    public void stop() {
        try {
            sseClients.forEach(ac -> {
                try { ac.complete(); } catch (Exception ignored) {}
            });
            sseClients.clear();

            if (tomcat != null) {
                tomcat.stop();
                tomcat.destroy();
            }
            log.info("Web server stopped");
        } catch (Exception e) {
            log.error("Error stopping web server", e);
        }
    }

    /**
     * Broadcast metrics to all connected SSE clients.
     * Called every second by the metrics collector.
     */
    public void broadcastMetrics(List<PoolMetricsSnapshot> snapshots) {
        String json;
        try {
            json = objectMapper.writeValueAsString(snapshots);
        } catch (Exception e) {
            log.error("Failed to serialize metrics", e);
            return;
        }

        sseClients.removeIf(ac -> {
            try {
                PrintWriter writer = ac.getResponse().getWriter();
                writer.write("data: " + json + "\n\n");
                writer.flush();
                return writer.checkError();
            } catch (Exception e) {
                return true; // remove disconnected client
            }
        });
    }

    // --- Servlets ---

    private class SseServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.setContentType("text/event-stream");
            resp.setCharacterEncoding("UTF-8");
            resp.setHeader("Cache-Control", "no-cache");
            resp.setHeader("Connection", "keep-alive");
            resp.setHeader("Access-Control-Allow-Origin", "*");

            AsyncContext asyncContext = req.startAsync();
            asyncContext.setTimeout(0);
            sseClients.add(asyncContext);

            resp.getWriter().write("data: {\"type\":\"connected\"}\n\n");
            resp.getWriter().flush();
        }
    }

    private class StartTestServlet extends HttpServlet {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.setContentType("application/json");
            resp.setHeader("Access-Control-Allow-Origin", "*");
            try {
                environment.startRegistered();
                resp.getWriter().write("{\"status\":\"started\"}");
            } catch (Exception e) {
                resp.setStatus(500);
                resp.getWriter().write("{\"error\":\"" + e.getMessage() + "\"}");
            }
        }
    }

    private class StopTestServlet extends HttpServlet {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.setContentType("application/json");
            resp.setHeader("Access-Control-Allow-Origin", "*");
            environment.shutdown();
            resp.getWriter().write("{\"status\":\"stopped\"}");
        }
    }

    private class ConfigServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.setContentType("application/json");
            resp.setHeader("Access-Control-Allow-Origin", "*");
            var config = environment.config();
            String json = objectMapper.writeValueAsString(new ConfigResponse(
                    config.numberOfUsers(),
                    config.rampUpTime().toSeconds(),
                    config.testDuration().toSeconds(),
                    config.connectionPoolSize()
            ));
            resp.getWriter().write(json);
        }
    }

    private class UpdateConfigServlet extends HttpServlet {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.setContentType("application/json");
            resp.setHeader("Access-Control-Allow-Origin", "*");
            try {
                ConfigRequest configReq = objectMapper.readValue(req.getInputStream(), ConfigRequest.class);
                var newConfig = io.loadstorm.api.environment.EnvironmentConfig.create()
                        .numberOfUsers(configReq.numberOfUsers())
                        .rampUpTime(Duration.ofSeconds(configReq.rampUpSeconds()))
                        .testDuration(Duration.ofSeconds(configReq.testDurationSeconds()))
                        .connectionPoolSize(configReq.connectionPoolSize());
                environment.applyWebConfig(newConfig);
                resp.getWriter().write("{\"status\":\"updated\"}");
            } catch (Exception e) {
                resp.setStatus(400);
                resp.getWriter().write("{\"error\":\"" + e.getMessage() + "\"}");
            }
        }
    }

    private class SnapshotServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.setContentType("application/json");
            resp.setHeader("Access-Control-Allow-Origin", "*");
            List<PoolMetricsSnapshot> snapshots = environment.metricsCollector().snapshot();
            resp.getWriter().write(objectMapper.writeValueAsString(snapshots));
        }
    }

    private class DashboardServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.setContentType("text/html");
            resp.setCharacterEncoding("UTF-8");
            resp.getWriter().write(DASHBOARD_HTML);
        }
    }

    // DTOs
    record ConfigResponse(int numberOfUsers, long rampUpSeconds, long testDurationSeconds, int connectionPoolSize) {}
    record ConfigRequest(int numberOfUsers, long rampUpSeconds, long testDurationSeconds, int connectionPoolSize) {}

    private static final String DASHBOARD_HTML = """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>LoadStorm Dashboard</title>
    <style>
        *{margin:0;padding:0;box-sizing:border-box}
        body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:#0f1117;color:#e1e4e8}
        .header{background:#161b22;border-bottom:1px solid #30363d;padding:16px 24px;display:flex;align-items:center;justify-content:space-between}
        .header h1{font-size:20px;font-weight:600}
        .header h1 span{color:#58a6ff}
        .badge{padding:4px 12px;border-radius:12px;font-size:12px;font-weight:600;text-transform:uppercase}
        .st-idle{background:#30363d;color:#8b949e}
        .st-run{background:#1b4332;color:#3fb950}
        .st-stop{background:#4a1e1e;color:#f85149}
        .controls{padding:16px 24px;display:flex;gap:12px;align-items:center}
        button{padding:8px 20px;border:1px solid #30363d;border-radius:6px;font-size:14px;font-weight:500;cursor:pointer;transition:all .15s}
        .b-go{background:#238636;color:#fff;border-color:#238636}.b-go:hover{background:#2ea043}.b-go:disabled{background:#1a5a2a;opacity:.6;cursor:not-allowed}
        .b-no{background:#da3633;color:#fff;border-color:#da3633}.b-no:hover{background:#e5534b}.b-no:disabled{opacity:.4;cursor:not-allowed}
        .cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:16px;padding:0 24px 16px}
        .card{background:#161b22;border:1px solid #30363d;border-radius:8px;padding:16px}
        .card-l{font-size:12px;color:#8b949e;text-transform:uppercase;letter-spacing:.5px;margin-bottom:4px}
        .card-v{font-size:28px;font-weight:700;font-variant-numeric:tabular-nums}
        .green{color:#3fb950}.red{color:#f85149}.blue{color:#58a6ff}.yellow{color:#d29922}
        .chart-box{margin:0 24px 16px;background:#161b22;border:1px solid #30363d;border-radius:8px;padding:16px}
        .chart-box h3{font-size:14px;color:#8b949e;margin-bottom:12px}
        canvas{width:100%%;height:250px}
        .tbl-box{margin:0 24px 24px;background:#161b22;border:1px solid #30363d;border-radius:8px;overflow:hidden}
        .tbl-box h3{font-size:14px;color:#8b949e;padding:16px 16px 12px}
        table{width:100%%;border-collapse:collapse}
        th{text-align:left;padding:8px 16px;font-size:12px;color:#8b949e;text-transform:uppercase;border-bottom:1px solid #30363d;background:#0d1117}
        td{padding:10px 16px;font-size:14px;border-bottom:1px solid #21262d;font-variant-numeric:tabular-nums}
        .conn{color:#3fb950}.disc{color:#f85149}
        .log{margin:0 24px 24px;background:#161b22;border:1px solid #30363d;border-radius:8px;padding:16px;max-height:180px;overflow-y:auto;font-family:monospace;font-size:12px;color:#8b949e}
    </style>
</head>
<body>
<div class="header">
    <h1><span>LoadStorm</span> Dashboard</h1>
    <div><span id="conn" class="disc">Disconnected</span>&nbsp;<span id="tst" class="badge st-idle">Idle</span></div>
</div>
<div class="controls">
    <button id="bGo" class="b-go" onclick="go()">Start Test</button>
    <button id="bNo" class="b-no" onclick="no()" disabled>Stop Test</button>
</div>
<div class="cards">
    <div class="card"><div class="card-l">Active Users</div><div id="mAU" class="card-v blue">0</div></div>
    <div class="card"><div class="card-l">Requests / sec</div><div id="mRPS" class="card-v yellow">0</div></div>
    <div class="card"><div class="card-l">Total Success</div><div id="mOK" class="card-v green">0</div></div>
    <div class="card"><div class="card-l">Total Failures</div><div id="mFAIL" class="card-v red">0</div></div>
    <div class="card"><div class="card-l">Avg Response (ms)</div><div id="mAVG" class="card-v">0</div></div>
</div>
<div class="chart-box"><h3>Requests per Second (per action)</h3><canvas id="cRPS"></canvas></div>
<div class="chart-box"><h3>Response Time ms (per action)</h3><canvas id="cRT"></canvas></div>
<div class="tbl-box">
    <h3>Actions</h3>
    <table><thead><tr><th>Action</th><th>Active</th><th>Success</th><th>Failures</th><th>Avg ms</th><th>p99 ms</th><th>RPS</th></tr></thead>
    <tbody id="tbl"></tbody></table>
</div>
<div class="log" id="log"></div>
<script>
const MP=120,CL=['#58a6ff','#3fb950','#d29922','#f85149','#bc8cff','#f0883e','#39d2c0','#e3b341'];
let sd={},run=false,es=null,c1,c2;
function initC(){
    const o={responsive:true,animation:false,scales:{x:{ticks:{color:'#8b949e',maxTicksLimit:10},grid:{color:'#21262d'}},y:{beginAtZero:true,ticks:{color:'#8b949e'},grid:{color:'#21262d'}}},plugins:{legend:{labels:{color:'#e1e4e8'}}}};
    c1=new Chart(document.getElementById('cRPS'),{type:'line',data:{labels:[],datasets:[]},options:{...o}});
    c2=new Chart(document.getElementById('cRT'),{type:'line',data:{labels:[],datasets:[]},options:{...o}});
}
function lg(m){const p=document.getElementById('log'),d=document.createElement('div');d.textContent=new Date().toLocaleTimeString()+' '+m;p.prepend(d);if(p.children.length>80)p.removeChild(p.lastChild)}
function sse(){
    if(es)es.close();
    es=new EventSource('/api/metrics/stream');
    es.onopen=()=>{document.getElementById('conn').textContent='Connected';document.getElementById('conn').className='conn';lg('SSE connected')};
    es.onmessage=(e)=>{try{const d=JSON.parse(e.data);if(!d.type)upd(d)}catch(x){}};
    es.onerror=()=>{document.getElementById('conn').textContent='Disconnected';document.getElementById('conn').className='disc'};
}
function upd(sn){
    if(!Array.isArray(sn)||!sn.length)return;
    let tA=0,tR=0,tS=0,tF=0,tAvg=0;const tl=new Date().toLocaleTimeString();
    const tb=document.getElementById('tbl');tb.innerHTML='';
    sn.forEach((s,i)=>{
        tA+=s.activeCount;tR+=s.requestsPerSecond;tS+=s.completedCount;tF+=s.failedCount;tAvg+=s.averageResponseTimeMs;
        const r=document.createElement('tr');
        r.innerHTML='<td style="color:'+CL[i%CL.length]+'">'+s.actionName+'</td><td>'+s.activeCount+'</td><td>'+s.completedCount+'</td><td>'+s.failedCount+'</td><td>'+s.averageResponseTimeMs.toFixed(1)+'</td><td>'+s.p99ResponseTimeMs.toFixed(1)+'</td><td>'+s.requestsPerSecond.toFixed(1)+'</td>';
        tb.appendChild(r);
        if(!sd[s.actionName])sd[s.actionName]={rps:[],rt:[],cl:CL[Object.keys(sd).length%CL.length]};
        const d=sd[s.actionName];d.rps.push(s.requestsPerSecond);d.rt.push(s.averageResponseTimeMs);
        if(d.rps.length>MP){d.rps.shift();d.rt.shift()}
    });
    document.getElementById('mAU').textContent=tA;
    document.getElementById('mRPS').textContent=tR.toFixed(1);
    document.getElementById('mOK').textContent=tS;
    document.getElementById('mFAIL').textContent=tF;
    document.getElementById('mAVG').textContent=(tAvg/sn.length).toFixed(1);
    const lb=c1.data.labels;lb.push(tl);if(lb.length>MP)lb.shift();
    const ds=e=>Object.entries(sd).map(([n,d])=>({label:n,data:d[e],borderColor:d.cl,backgroundColor:'transparent',tension:.3,pointRadius:0,borderWidth:2}));
    c1.data.datasets=ds('rps');c2.data.datasets=ds('rt');c2.data.labels=[...lb];
    c1.update('none');c2.update('none');
}
async function go(){
    try{const r=await fetch('/api/test/start',{method:'POST'});const d=await r.json();
    if(d.status==='started'){run=true;document.getElementById('bGo').disabled=true;document.getElementById('bNo').disabled=false;
    document.getElementById('tst').textContent='Running';document.getElementById('tst').className='badge st-run';lg('Test started')}
    else lg('Error: '+(d.error||'unknown'))}catch(e){lg('Start failed: '+e.message)}
}
async function no(){
    try{await fetch('/api/test/stop',{method:'POST'});run=false;document.getElementById('bGo').disabled=false;document.getElementById('bNo').disabled=true;
    document.getElementById('tst').textContent='Stopped';document.getElementById('tst').className='badge st-stop';lg('Test stopped')}catch(e){lg('Stop failed: '+e.message)}
}
const sc=document.createElement('script');sc.src='https://cdn.jsdelivr.net/npm/chart.js@4.4.1/dist/chart.umd.min.js';
sc.onload=()=>{initC();sse()};document.head.appendChild(sc);
</script>
</body>
</html>
""";
}