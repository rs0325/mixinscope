package io.github.rs0325.mixinscope.report;

import io.github.rs0325.mixinscope.core.Util;
import io.github.rs0325.mixinscope.core.BoundedOutputStream;
import io.github.rs0325.mixinscope.model.Model.*;
import io.github.rs0325.mixinscope.rules.FindingGroups;
import java.io.IOException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.io.*;
import java.util.*;

public final class Reports {
    private Reports() {}
    public static String json(Report report) throws IOException {
        return json(report,64L<<20);
    }
    public static String json(Report report,long maximum) throws IOException {return serialize(report,maximum,false);}
    public static void validatePayload(Report report,long maximum)throws IOException {
        FindingGroups.refresh(report);Util.JSON.writerWithDefaultPrettyPrinter().writeValue(new BoundedOutputStream(OutputStream.nullOutputStream(),maximum),report);
    }
    private static String serialize(Report report,long maximum,boolean htmlSafe)throws IOException {
        FindingGroups.refresh(report);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();BoundedOutputStream output=new BoundedOutputStream(bytes,maximum);
        try(JsonGenerator generator=Util.JSON.getFactory().createGenerator(output)) {
            if(htmlSafe)generator.setCharacterEscapes(new CharacterEscapes(){
                private final int[] escapes=CharacterEscapes.standardAsciiEscapesForJSON();
                {escapes['<']=ESCAPE_CUSTOM;escapes['>']=ESCAPE_CUSTOM;escapes['&']=ESCAPE_CUSTOM;}
                @Override public int[] getEscapeCodesForAscii(){return escapes;}
                @Override public SerializableString getEscapeSequence(int c){return switch(c){
                    case '<'->new SerializedString("\\u003c");case '>'->new SerializedString("\\u003e");case '&'->new SerializedString("\\u0026");
                    case 0x2028->new SerializedString("\\u2028");case 0x2029->new SerializedString("\\u2029");default->null;};}
            });
            Util.JSON.writerWithDefaultPrettyPrinter().writeValue(generator,report);
        }
        output.write('\n');return bytes.toString(StandardCharsets.UTF_8);
    }
    public static String text(Report report, List<Finding> findings) {
        StringBuilder out = new StringBuilder("MixinScope — static compatibility analysis\n");
        Map<String, Finding> byId = new HashMap<>(); findings.forEach(f -> byId.put(f.id, f));
        for (TargetGroup group : FindingGroups.summarize(findings)) {
            out.append("\nPotential Conflict Detected").append(group.activeMaxSeverity == null ? " [ALL SUPPRESSED]" : "").append('\n');
            out.append("Target: ").append(group.target.display()).append('\n');
            out.append("Profile: ").append(group.target.gameVersion()).append(" / ").append(group.target.loaderProfile()).append(" / ").append(group.target.namespace()).append('\n');
            out.append("Affected Mods: ").append(String.join(", ", group.mods)).append('\n');
            out.append("Risk: ").append(group.maxSeverity).append("\nConfidence at maximum risk: ").append(group.maxSeverityConfidence).append('\n');
            out.append("Active risk: ").append(group.activeMaxSeverity == null ? "none (all suppressed)" : group.activeMaxSeverity).append('\n');
            out.append("Rules: ").append(String.join(", ", group.rules)).append("\nScenarios: ").append(group.scenarios).append('\n');
            for (Finding f : group.findingIds.stream().map(byId::get).sorted(Comparator.comparing((Finding f) -> f.rule).thenComparing(f -> f.id)).toList()) {
                out.append("\n  Rule: ").append(f.rule).append(" / ").append(f.severity).append(" / ").append(f.confidence)
                        .append(f.suppressed ? " [SUPPRESSED]" : "").append("\n  Finding ID: ").append(f.id).append("\n  Affected Mods: ").append(f.mods)
                        .append("\n  Scenarios: ").append(f.scenarios).append("\n  Reason: ").append(f.reason).append('\n');
                for (String e : f.evidence) out.append("  Evidence: ").append(e).append('\n');
                for (String l : f.limitations) out.append("  Limitation: ").append(l).append('\n');
                if (f.suppressed) out.append("  Suppression: ").append(f.suppressionReason).append('\n');
            }
        }
        out.append("\nTarget inventory:\n");
        report.targetIndex.forEach((target, ids) -> out.append("  ").append(target).append(" <- ").append(ids.size()).append(" modification(s)\n"));
        Coverage c = report.coverage;
        out.append("\nCoverage: artifacts=").append(c.artifacts).append(", failed=").append(c.failedArtifacts)
                .append(", modifications=").append(c.modifications).append(", unresolved=").append(c.unresolvedTargets)
                .append(", ambiguous=").append(c.ambiguousTargets).append(", conditional/unknown=").append(c.unknownApplicability)
                .append(", skipped pairs=").append(c.skippedPairs).append(", skipped targets=").append(c.skippedTargets).append(", diagnostics=").append(c.diagnostics.size()).append('\n');
        out.append("Findings: ").append(findings.size()).append(" shown / ").append(report.findings.size()).append(" total, suppressed=").append(c.suppressedFindings).append('\n');
        if (c.partial) out.append("PARTIAL: some inputs or analysis steps failed.\n");
        out.append("No findings does not establish compatibility. Mods and plugins were not executed.\n");
        for (Transformer t : report.transformers) out.append("Other transformer: ").append(t.kind()).append(" ").append(t.resource()).append(" — ").append(t.limitation()).append('\n');
        if (!report.baseline.isEmpty()) out.append("Baseline: ").append(report.baseline).append('\n');
        return out.toString();
    }
    public static String html(Report report) throws IOException {
        return html(report,64L<<20);
    }
    public static String html(Report report,long maximum)throws IOException {
        // Data is embedded as JSON in a non-executable script. Escape HTML parser terminators.
        String data=serialize(report,maximum,true);
        return HTML.replace("__REPORT_DATA__", data);
    }
    private static final String HTML = """
        <!doctype html>
        <html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src 'none'; connect-src 'none'">
        <title>MixinScope report</title>
        <style>
        :root{color-scheme:light dark;font-family:system-ui,sans-serif}*{box-sizing:border-box}body{max-width:1100px;margin:32px auto;padding:0 20px;line-height:1.5;overflow-wrap:anywhere}
        input,select,button{font:inherit;padding:6px;margin:4px;min-width:0;max-width:calc(100% - 8px)}label{display:inline-block;max-width:100%}article,details{border:1px solid #8888;border-radius:8px;padding:16px;margin:12px 0}
        h1,h2,h3{line-height:1.2}.HIGH{border-left:6px solid #d85050}.MEDIUM{border-left:6px solid #c59524}.LOW{border-left:6px solid #449478}
        section.group{border:1px solid #8888;border-radius:8px;padding:12px;margin:8px 0}a:focus-visible,button:focus-visible,input:focus-visible,select:focus-visible,summary:focus-visible,[tabindex]:focus-visible{outline:3px solid #528cff;outline-offset:3px}
        pre{white-space:pre-wrap;overflow-wrap:anywhere}.muted{opacity:.75}li{overflow-wrap:anywhere}a{color:inherit}summary,button{cursor:pointer}
        .edge{padding:4px 12px;margin:4px;background:#8882;border-radius:4px}.badge{font-size:.9em;font-weight:bold}
        nav{display:flex;flex-wrap:wrap;gap:8px 20px}nav a{padding:4px}
        @media(max-width:600px){body{margin:16px auto;padding:0 12px}#filters{display:grid;grid-template-columns:minmax(0,1fr);gap:8px}#filters label{display:grid;min-width:0}#filters label.check{display:flex;align-items:center}#filters button{justify-self:start}}
        </style></head><body>
        <h1>MixinScope</h1><p>Static compatibility analysis. No findings does not establish compatibility.</p>
        <nav aria-label="Report sections"><a id="jump-filters" href="#filters">Filters</a><a id="jump-findings" href="#count">Findings</a><a id="jump-inventory" href="#inventory">Inventory</a></nav>
        <main>
        <p id="coverage" role="status"></p>
        <form id="filters" aria-label="Finding filters"><label>Search <input id="search" type="search"></label>
        <label>Minimum risk <select id="risk"><option value="">All</option><option>HIGH</option><option>MEDIUM</option><option>LOW</option></select></label>
        <label>Confidence <select id="confidence"><option value="">All</option><option>HIGH</option><option>MEDIUM</option><option>LOW</option></select></label>
        <label>Mod <select id="mod"><option value="">All</option></select></label>
        <label>Target <select id="target"><option value="">All</option></select></label>
        <label class="check"><input id="suppressed" type="checkbox" checked> Include suppressed</label>
        <button type="reset">Reset</button></form>
        <h2>Target summaries</h2><p id="group-count" aria-live="polite"></p><div id="groups"></div>
        <h2>Individual findings</h2><p id="count" aria-live="polite" tabindex="-1"></p><div id="findings"></div>
        <button id="more" type="button">Show 100 more findings</button>
        <details id="inventory"><summary id="inventory-summary">Inventory and target graph</summary><p>Choose a mod to list its targets, or a target to inspect all of its modifications, including declared ineligible modifications. At most 200 entries are drawn.</p><div id="graph"></div></details>
        <details><summary>Assumptions and analysis coverage</summary><pre id="diagnostics"></pre></details>
        <details><summary>Suppression and baseline</summary><pre id="history"></pre></details>
        </main>
        <script id="report" type="application/json">__REPORT_DATA__</script>
        <script>
        'use strict';
        const report=JSON.parse(document.getElementById('report').textContent),byId=new Map(report.modifications.map(m=>[m.id,m]));
        const get=id=>document.getElementById(id),display=t=>t.owner+'.'+t.name+t.descriptor;
        const key=t=>t.gameVersion+'|'+t.loaderProfile+'|'+t.namespace+'|'+display(t),label=t=>display(t)+' ['+t.gameVersion+' / '+t.loaderProfile+' / '+t.namespace+']';
        const el=(tag,text,cls)=>{const n=document.createElement(tag);if(text!==undefined)n.textContent=text;if(cls)n.className=cls;return n;};
        let limit=100;
        for(const name of Object.keys(report.modIndex))get('mod').append(el('option',name));
        const targets=new Map(report.modifications.flatMap(m=>m.targets.flatMap(t=>[...(t.key?[t.key]:[]),...t.candidateKeys]).map(t=>[key(t),t])));
        for(const f of report.findings)targets.set(key(f.target),f.target);
        for(const [id,target]of [...targets].sort(([a],[b])=>a.localeCompare(b))){const option=el('option',label(target));option.value=id;get('target').append(option);}
        if(report.displayFilter.mod&&!report.modIndex[report.displayFilter.mod])get('mod').append(el('option',report.displayFilter.mod));
        get('mod').value=report.displayFilter.mod||'';get('search').value=report.displayFilter.target||'';get('risk').value=report.displayFilter.minSeverity||'';
        get('coverage').textContent='Artifacts: '+report.coverage.artifacts+' · Modifications: '+report.coverage.modifications+' · Findings: '+report.findings.length+' · Unresolved: '+report.coverage.unresolvedTargets+' · Ambiguous: '+report.coverage.ambiguousTargets+(report.coverage.partial?' · PARTIAL':'');
        get('diagnostics').textContent=JSON.stringify({environment:report.environment,assumptions:report.assumptions,coverage:report.coverage,transformers:report.transformers},null,2);
        get('history').textContent=JSON.stringify({baseline:report.baseline,suppressed:report.findings.filter(f=>f.suppressed).map(f=>({id:f.id,reason:f.suppressionReason}))},null,2);
        function render(){
          const q=get('search').value.toLowerCase(),risk=get('risk').value,conf=get('confidence').value,mod=get('mod').value,target=get('target').value;
          const levels={LOW:0,MEDIUM:1,HIGH:2};
          const items=report.findings.filter(f=>(!risk||levels[f.severity]>=levels[risk])&&(!conf||f.confidence===conf)&&(!mod||f.mods.includes(mod))&&(!target||key(f.target)===target)&&(get('suppressed').checked||!f.suppressed)&&JSON.stringify(f).toLowerCase().includes(q));
          get('findings').replaceChildren();get('count').textContent=items.length+' matching / '+report.findings.length+' total; showing '+Math.min(limit,items.length);
          const groups=new Map();for(const f of items){const k=key(f.target);if(!groups.has(k))groups.set(k,[]);groups.get(k).push(f);}
          get('groups').replaceChildren();get('group-count').textContent=groups.size+' matching targets / '+report.targetGroups.length+' total; summaries reflect the current filters.';
          for(const [id,members]of [...groups].slice(0,limit)){
            const max=members.reduce((risk,f)=>levels[f.severity]>levels[risk]?f.severity:risk,'LOW');
            const active=members.filter(f=>!f.suppressed).reduce((risk,f)=>risk===null||levels[f.severity]>levels[risk]?f.severity:risk,null);
            const confidence=members.filter(f=>f.severity===max).reduce((conf,f)=>levels[f.confidence]<levels[conf]?f.confidence:conf,'HIGH');
            const section=el('section',undefined,'group '+max);section.dataset.targetKey=id;
            section.append(el('h3',label(members[0].target)),el('p','Maximum risk: '+max+' · '+confidence+' confidence · Active risk: '+(active||'none (all suppressed)')+' · '+members.length+' findings · Rules: '+[...new Set(members.map(f=>f.rule))].join(', ')));
            const button=el('button','Inspect target');button.type='button';button.setAttribute('aria-label','Inspect target '+label(members[0].target));button.addEventListener('click',()=>inspect(id));section.append(button);get('groups').append(section);
          }
          for(const f of items.slice(0,limit)){
            const card=el('article',undefined,f.severity);card.dataset.findingId=f.id;
            card.append(el('h3',display(f.target)),el('p',f.severity+' · '+f.confidence+' confidence · '+f.rule+(f.suppressed?' · SUPPRESSED':''),'badge'));
            const mods=el('p','Affected mods: ');
            for(const name of f.mods){const b=el('button',name);b.type='button';b.setAttribute('aria-label','Filter findings by mod '+name);b.addEventListener('click',()=>{get('mod').value=name;limit=100;render();get('mod').focus();});mods.append(b);}
            card.append(mods,el('p',f.reason));const b=el('button','Inspect target');b.type='button';b.setAttribute('aria-label','Inspect target '+label(f.target));b.addEventListener('click',()=>inspect(key(f.target)));card.append(b);
            const details=el('details');details.append(el('summary','Evidence, assumptions and limitations'),el('pre',JSON.stringify({id:f.id,modifications:f.modifications.map(id=>byId.get(id)),scenarios:f.scenarios,evidence:f.evidence,limitations:f.limitations,suppression:f.suppressionReason},null,2)));card.append(details);get('findings').append(card);
          }
          get('more').hidden=items.length<=limit;if(get('more').hidden&&document.activeElement===get('more'))get('count').focus();get('graph').replaceChildren();
          if(target){
            const ms=report.modifications.filter(m=>m.targets.some(t=>t.key&&key(t.key)===target||t.candidateKeys.some(k=>key(k)===target)));
            get('graph').append(el('h3',label(targets.get(target))),el('p',ms.length+' modifications; '+Math.min(200,ms.length)+' shown'));
            for(const m of ms.slice(0,200)){const a=report.artifacts.find(a=>a.hash===m.artifact);get('graph').append(el('div',(a?a.mods.map(x=>x.id).join(', '):m.artifact)+' → '+m.mixin+' → '+m.handler+m.handlerDescriptor+' @'+m.kind+' · '+m.applicability,'edge'));}
          }else if(mod){
            const ids=report.modIndex[mod]||[];get('graph').append(el('h3',mod),el('p',ids.length+' declared targets; '+Math.min(200,ids.length)+' shown'));
            for(const id of ids.slice(0,200)){const t=targets.get(id);if(!t)continue;const button=el('button',label(t));button.type='button';button.addEventListener('click',()=>inspect(id));get('graph').append(button);}
          }else get('graph').append(el('p','Select a mod or target above to inspect its inventory.'));
        }
        function inspect(id){get('target').value=id;get('inventory').open=true;limit=100;render();get('target').focus();}
        for(const [link,destination]of [['jump-filters','search'],['jump-findings','count'],['jump-inventory','inventory-summary']])get(link).addEventListener('click',event=>{event.preventDefault();if(destination==='inventory-summary')get('inventory').open=true;get(destination).focus();});
        get('filters').addEventListener('input',()=>{limit=100;render();});get('filters').addEventListener('submit',e=>e.preventDefault());
        get('filters').addEventListener('reset',()=>setTimeout(()=>{limit=100;render();},0));get('more').addEventListener('click',()=>{limit+=100;render();});render();
        </script></body></html>
        """;
}
