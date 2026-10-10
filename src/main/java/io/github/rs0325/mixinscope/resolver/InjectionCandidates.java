package io.github.rs0325.mixinscope.resolver;

import io.github.rs0325.mixinscope.core.Util;
import io.github.rs0325.mixinscope.model.Model.*;
import java.util.*;
import java.util.function.UnaryOperator;

/** Normalize declared point candidates without claiming that an instruction exists. */
final class InjectionCandidates {
    private InjectionCandidates() {}
    static void normalize(Modification m,TinyMappings mappings,String input,String output,UnaryOperator<String> references,Report report) {
        try {
            for(var at:m.at)m.normalizedAt.add(point(at,m,mappings,input,output,references));
            for(Object raw:Util.list(m.options.get("slice"))) {
                Map<String,Object> slice=new TreeMap<>(Util.map(raw));
                for(String end:List.of("from","to"))if(slice.containsKey(end))slice.put(end,point(Util.map(slice.get(end)),m,mappings,input,output,references));
                m.normalizedSlices.add(slice);
            }
            m.pointCandidatesNormalized=true;
        } catch(IllegalArgumentException error) {
            report.diagnostic("POINT_CANDIDATE_NORMALIZATION_UNVERIFIED",m.id,error.getMessage(),false);
        }
    }
    private static Map<String,Object> point(Map<String,Object> raw,Modification m,TinyMappings mappings,String input,String output,UnaryOperator<String> references) {
        Map<String,Object> at=new TreeMap<>(raw);String target=Util.str(raw.get("target"),"");
        boolean remap=Util.bool(m.options.get("remap"),true)&&Util.bool(at.get("remap"),true);
        if(!remap&&mappings!=null&&!input.equals(output)&&!target.isEmpty())throw new IllegalArgumentException("Literal remap=false point remains in input namespace; output reference identity is unverified");
        if(remap&&!target.isEmpty())target=references.apply(target);
        if(remap&&mappings!=null&&!target.isEmpty()) {
            switch(Util.str(at.get("value"),"")) {
                case "INVOKE" -> {
                    var s=TargetResolver.parse(target,"");
                    if(s.owner().isEmpty()||s.descriptor().isEmpty()||s.name().contains("*"))throw new IllegalArgumentException("Non-exact INVOKE mapping is not verified");
                    target="L"+mappings.owner(s.owner())+";"+mappings.method(s.owner(),s.name(),s.descriptor())+mappings.descriptor(s.descriptor());
                }
                case "FIELD" -> {
                    int end=target.indexOf(';'),colon=target.indexOf(':');
                    if(!target.startsWith("L")||end<=1||colon<=end+1||colon==target.length()-1)throw new IllegalArgumentException("Non-exact FIELD mapping is not verified");
                    String owner=target.substring(1,end),name=target.substring(end+1,colon),desc=target.substring(colon+1);
                    target="L"+mappings.owner(owner)+";"+mappings.field(owner,name,desc)+":"+mappings.descriptor(desc);
                }
                case "NEW" -> {
                    if(target.contains("("))throw new IllegalArgumentException("Constructor-descriptor NEW mapping is not supported");
                    String owner=target.startsWith("L")&&target.endsWith(";")?target.substring(1,target.length()-1):Util.owner(target);
                    target=mappings.owner(owner);
                }
                default -> throw new IllegalArgumentException("Custom injection-point target mapping is not verified");
            }
        }
        if(raw.containsKey("target"))at.put("target",target);
        if(remap&&mappings!=null&&at.containsKey("constant")) {
            List<Map<String,Object>> constants=new ArrayList<>();
            for(Object value:Util.list(at.get("constant"))) {
                Map<String,Object> constant=new TreeMap<>(Util.map(value));
                if(constant.containsKey("classValue"))constant.put("classValue",mappings.descriptor(constant.get("classValue").toString()));
                constants.add(constant);
            }
            at.put("constant",constants);
        }
        return at;
    }
}
