import fs from 'node:fs';
import assert from 'node:assert/strict';
import Ajv2020 from 'ajv/dist/2020.js';

const schema=JSON.parse(fs.readFileSync('docs/report.schema.json','utf8'));
const validator=new Ajv2020({allErrors:true,strict:true}).compile(schema);
const paths=process.argv.slice(2);
if(paths.length===0)paths.push('build/demo/report.json','build/demo/cli-report.json');
for(const path of paths){
  const report=JSON.parse(fs.readFileSync(path,'utf8'));
  assert.equal(validator(report),true,path+': '+JSON.stringify(validator.errors));
  const findings=new Map(report.findings.map(f=>[f.id,f]));
  assert.equal(findings.size,report.findings.length,'Unique finding IDs');
  const grouped=new Set(report.targetGroups.flatMap(g=>g.findingIds));
  assert.deepEqual([...grouped].sort(),[...findings.keys()].sort(),'Every finding has a target summary');
  assert.equal(report.targetGroups.reduce((count,g)=>count+g.findingIds.length,0),report.findings.length,'Each finding belongs to exactly one target summary');
  for(const group of report.targetGroups)for(const id of group.findingIds)assert.deepEqual(findings.get(id).target,group.target,'Group target identity matches finding');
  assert.ok(report.visibleFindingIds.every(id=>findings.has(id)),'Display IDs reference complete findings');
}
process.stdout.write('JSON Schema 1.1 and summary references validated: '+paths.length+' reports.\n');
