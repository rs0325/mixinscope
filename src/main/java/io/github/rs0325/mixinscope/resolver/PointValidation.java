package io.github.rs0325.mixinscope.resolver;

import io.github.rs0325.mixinscope.core.Util;
import java.util.*;

/** Supported declaration grammar only. Validation does not establish instruction existence. */
final class PointValidation {
    private PointValidation() {}
    static final class Unsupported extends IllegalArgumentException { Unsupported(String message){super(message);} }
    static void validate(Map<String,Object> at) {
        String kind=Util.str(at.get("value"),"");
        if(!Set.of("HEAD","RETURN","TAIL","INVOKE","FIELD","NEW","CONSTANT").contains(kind))fail("Unsupported injection point: "+kind);
        if(!Util.list(at.get("args")).isEmpty())fail("Injection-point args are not supported: "+kind);
        if(Util.integer(at.get("ordinal"),-1)<-1)fail("Invalid ordinal");
        if(!Set.of("NONE","BEFORE","AFTER","BY").contains(Util.str(at.get("shift"),"NONE")))fail("Unknown shift");
        String target=Util.str(at.get("target"),"");
        try {
            switch(kind) {
                case "INVOKE" -> {
                    var selector=TargetResolver.parse(target,"");
                    if(selector.owner().isEmpty()||selector.descriptor().isEmpty()||selector.name().contains("*"))fail("INVOKE requires exact owner, name and descriptor");
                }
                case "FIELD" -> {
                    int end=target.indexOf(';'),colon=target.indexOf(':');
                    if(!target.startsWith("L")||end<=1||colon<=end+1||colon==target.length()-1||target.substring(end+1,colon).contains("*"))fail("FIELD requires exact Lowner;name:descriptor");
                    if(!TargetResolver.validFieldDescriptor(target.substring(colon+1)))fail("Invalid FIELD descriptor");
                }
                case "NEW" -> {
                    String owner=target.startsWith("L")&&target.endsWith(";")?target.substring(1,target.length()-1):target;
                    if(owner.isEmpty()||owner.indexOf('(')>=0||owner.indexOf('[')>=0||owner.indexOf(';')>=0||owner.indexOf('*')>=0)fail("NEW requires a class owner; constructor-descriptor queries are unsupported");
                }
                case "CONSTANT" -> {
                    List<Object> constants=Util.list(at.get("constant"));
                    if(constants.isEmpty())fail("CONSTANT without explicit @Constant unsupported");
                    for(Object value:constants) {
                        var constant=Util.map(value);
                        if(constant.containsKey("expandZeroConditions")||constant.containsKey("ordinal")||constant.containsKey("slice"))fail("Constant expansion / per-constant ordinal / slice unsupported");
                        if(!Boolean.TRUE.equals(constant.get("nullValue"))&&Collections.disjoint(constant.keySet(),List.of("intValue","longValue","floatValue","doubleValue","stringValue","classValue")))fail("CONSTANT requires an explicit typed value");
                    }
                }
                default -> { }
            }
        } catch(Unsupported error){throw error;}
        catch(IllegalArgumentException error){fail(error.getMessage());}
    }
    private static void fail(String message){throw new Unsupported(message);}
}
