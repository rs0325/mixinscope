package io.github.rs0325.mixinscope.report;

import io.github.rs0325.mixinscope.core.Util;
import io.github.rs0325.mixinscope.model.Model.*;
import java.io.IOException;
import java.util.*;

public final class Reports {
    private Reports() {}
    public static String json(Report report) throws IOException { return Util.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n"; }
    public static String text(Report report, List<Finding> findings) {
        StringBuilder out = new StringBuilder("MixinScope — static compatibility analysis\n");
        for (Finding f : findings) {
            out.append("\nPotential Conflict Detected").append(f.suppressed ? " [SUPPRESSED]" : "").append('\n');
            out.append("Target: ").append(f.target.display()).append('\n');
            out.append("Affected Mods: ").append(String.join(", ", f.mods)).append('\n');
            out.append("Risk: ").append(f.severity).append("\nConfidence: ").append(f.confidence).append("\nRule: ").append(f.rule).append('\n');
            out.append("Scenarios: ").append(f.scenarios).append("\nReason: ").append(f.reason).append('\n');
            for (String e : f.evidence) out.append("Evidence: ").append(e).append('\n');
            for (String l : f.limitations) out.append("Limitation: ").append(l).append('\n');
            if (f.suppressed) out.append("Suppression: ").append(f.suppressionReason).append('\n');
        }
        out.append("\nTarget inventory:\n");
        report.targetIndex.forEach((target, ids) -> out.append("  ").append(target).append(" <- ").append(ids.size()).append(" modification(s)\n"));
        Coverage c = report.coverage;
        out.append("\nCoverage: artifacts=").append(c.artifacts).append(", failed=").append(c.failedArtifacts)
                .append(", modifications=").append(c.modifications).append(", unresolved=").append(c.unresolvedTargets)
                .append(", ambiguous=").append(c.ambiguousTargets).append(", conditional/unknown=").append(c.unknownApplicability)
                .append(", skipped pairs=").append(c.skippedPairs).append(", diagnostics=").append(c.diagnostics.size()).append('\n');
        out.append("Findings: ").append(findings.size()).append(" shown / ").append(report.findings.size()).append(" total, suppressed=").append(c.suppressedFindings).append('\n');
        if (c.partial) out.append("PARTIAL: some inputs or analysis steps failed.\n");
        out.append("No findings does not establish compatibility. Mods and plugins were not executed.\n");
        for (Transformer t : report.transformers) out.append("Other transformer: ").append(t.kind()).append(" ").append(t.resource()).append(" — ").append(t.limitation()).append('\n');
        if (!report.baseline.isEmpty()) out.append("Baseline: ").append(report.baseline).append('\n');
        return out.toString();
    }
    public static String html(Report report) throws IOException {
        // Data is embedded as JSON in a non-executable script. Escape HTML parser terminators.
        String data = json(report).replace("&", "\\u0026").replace("<", "\\u003c").replace(">", "\\u003e").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
        return HTML.replace("__REPORT_DATA__", data);
    }
    private static final String HTML = """
        <!doctype html>
        <html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src 'none'; connect-src 'none'">
        <title>MixinScope report</title>
        <style>
        :root{color-scheme:light dark;font-family:system-ui,sans-serif}body{max-width:1100px;margin:32px auto;padding:0 20px;line-height:1.5}
        input,select,button{font:inherit;padding:6px;margin:4px}label{display:inline-block}article,details{border:1px solid #8888;border-radius:8px;padding:16px;margin:12px 0}
        h1,h2,h3{line-height:1.2}.HIGH{border-left:6px solid #d85050}.MEDIUM{border-left:6px solid #c59524}.LOW{border-left:6px solid #449478}
        pre{white-space:pre-wrap;overflow-wrap:anywhere}.muted{opacity:.75}li{overflow-wrap:anywhere}a{color:inherit}summary,button{cursor:pointer}
        .edge{padding:4px 12px;margin:4px;background:#8882;border-radius:4px}.badge{font-size:.9em;font-weight:bold}
        </style></head><body>
        <h1>MixinScope</h1><p>Static compatibility analysis. No findings does not establish compatibility.</p>
        <p id="coverage" role="status"></p>
        <form id="filters"><label>Search <input id="search" type="search"></label>
        <label>Minimum risk <select id="risk"><option value="">All</option><option>HIGH</option><option>MEDIUM</option><option>LOW</option></select></label>
        <label>Confidence <select id="confidence"><option value="">All</option><option>HIGH</option><option>MEDIUM</option><option>LOW</option></select></label>
        <label>Mod <select id="mod"><option value="">All</option></select></label>
        <label>Target <select id="target"><option value="">All</option></select></label>
        <label><input id="suppressed" type="checkbox" checked> Include suppressed</label>
        <button type="reset">Reset</button></form>
        <h2>Findings</h2><p id="count" aria-live="polite"></p><div id="findings"></div>
        <button id="more" type="button">Show 100 more findings</button>
        <details><summary>Inventory and target graph</summary><p>Choose a target to inspect its modifications; at most 200 entries are drawn.</p><div id="graph"></div></details>
        <details><summary>Assumptions and analysis coverage</summary><pre id="diagnostics"></pre></details>
        <details><summary>Suppression and baseline</summary><pre id="history"></pre></details>
        <script id="report" type="application/json">__REPORT_DATA__</script>
        <script>
        'use strict';
        const report=JSON.parse(document.getElementById('report').textContent),byId=new Map(report.modifications.map(m=>[m.id,m]));
        const get=id=>document.getElementById(id),display=t=>t.owner+'.'+t.name+t.descriptor;
        const el=(tag,text,cls)=>{const n=document.createElement(tag);if(text!==undefined)n.textContent=text;if(cls)n.className=cls;return n;};
        let limit=100;
        for(const name of Object.keys(report.modIndex))get('mod').append(el('option',name));
        const targets=[...new Set(report.findings.map(f=>display(f.target)).concat(report.modifications.flatMap(m=>m.targets.filter(t=>t.key).map(t=>display(t.key)))))].sort();
        for(const target of targets)get('target').append(el('option',target));
        get('mod').value=report.displayFilter.mod||'';get('search').value=report.displayFilter.target||'';get('risk').value=report.displayFilter.minSeverity||'';
        get('coverage').textContent='Artifacts: '+report.coverage.artifacts+' · Modifications: '+report.coverage.modifications+' · Findings: '+report.findings.length+' · Unresolved: '+report.coverage.unresolvedTargets+' · Ambiguous: '+report.coverage.ambiguousTargets+(report.coverage.partial?' · PARTIAL':'');
        get('diagnostics').textContent=JSON.stringify({environment:report.environment,assumptions:report.assumptions,coverage:report.coverage,transformers:report.transformers},null,2);
        get('history').textContent=JSON.stringify({baseline:report.baseline,suppressed:report.findings.filter(f=>f.suppressed).map(f=>({id:f.id,reason:f.suppressionReason}))},null,2);
        function render(){
          const q=get('search').value.toLowerCase(),risk=get('risk').value,conf=get('confidence').value,mod=get('mod').value,target=get('target').value;
          const levels={LOW:0,MEDIUM:1,HIGH:2};
          const items=report.findings.filter(f=>(!risk||levels[f.severity]>=levels[risk])&&(!conf||f.confidence===conf)&&(!mod||f.mods.includes(mod))&&(!target||display(f.target)===target)&&(get('suppressed').checked||!f.suppressed)&&JSON.stringify(f).toLowerCase().includes(q));
          get('findings').replaceChildren();get('count').textContent=items.length+' matching / '+report.findings.length+' total; showing '+Math.min(limit,items.length);
          for(const f of items.slice(0,limit)){
            const card=el('article',undefined,f.severity);card.dataset.findingId=f.id;
            card.append(el('h3',display(f.target)),el('p',f.severity+' · '+f.confidence+' confidence · '+f.rule+(f.suppressed?' · SUPPRESSED':''),'badge'));
            const mods=el('p','Affected mods: ');
            for(const name of f.mods){const b=el('button',name);b.type='button';b.addEventListener('click',()=>{get('mod').value=name;render();});mods.append(b);}
            card.append(mods,el('p',f.reason));const b=el('button','Inspect target');b.type='button';b.addEventListener('click',()=>{get('target').value=display(f.target);render();});card.append(b);
            const details=el('details');details.append(el('summary','Evidence, assumptions and limitations'),el('pre',JSON.stringify({id:f.id,modifications:f.modifications.map(id=>byId.get(id)),scenarios:f.scenarios,evidence:f.evidence,limitations:f.limitations,suppression:f.suppressionReason},null,2)));card.append(details);get('findings').append(card);
          }
          get('more').hidden=items.length<=limit;get('graph').replaceChildren();
          if(target){
            const ms=report.modifications.filter(m=>m.targets.some(t=>t.key&&display(t.key)===target));
            get('graph').append(el('h3',target),el('p',ms.length+' modifications; '+Math.min(200,ms.length)+' shown'));
            for(const m of ms.slice(0,200)){const a=report.artifacts.find(a=>a.hash===m.artifact);get('graph').append(el('div',(a?a.mods.map(x=>x.id).join(', '):m.artifact)+' → '+m.mixin+' → '+m.handler+m.handlerDescriptor+' @'+m.kind,'edge'));}
          }else get('graph').append(el('p','Select a target above to draw its graph.'));
        }
        get('filters').addEventListener('input',()=>{limit=100;render();});get('filters').addEventListener('submit',e=>e.preventDefault());
        get('filters').addEventListener('reset',()=>setTimeout(()=>{limit=100;render();},0));get('more').addEventListener('click',()=>{limit+=100;render();});render();
        </script></body></html>
        """;
}
