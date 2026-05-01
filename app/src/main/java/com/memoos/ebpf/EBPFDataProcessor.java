package com.memoos.ebpf;
import java.math.*;
import java.util.regex.*;
import java.util.*;
import java.io.*;
import io.github.javaherobrine.format.*;
public class EBPFDataProcessor {
    private static final Set<String> SPEC_KEYS=Set.of("pid","code","to_proc");
    private static final Pattern LINE_RE = Pattern.compile(
            "^\\s*(?<task>.+)-(?<tid>\\d+)\\s+\\[(?<cpu>\\d+)\\].*?\\s+(?<ts>\\d+\\.\\d+): bpf_trace_printk: (?<msg>MEMO_\\w+)\\s*(?<rest>.*)$"
    );
    private static final Pattern KV_RE = Pattern.compile("(\\w+)=([^=]+?)(?=\\s+\\w+=|$)");
    private static String evidenceCategory(String str){
        if(str == null || str.isEmpty()){
            return null;
        }
        if(str.endsWith(".jar") || str.contains("/framework/")){
            return "java_framework_or_classpath";
        }
        if(str.endsWith(".so") || str.contains("/lib64/")){
            return "native_library";
        }
        if(str.contains("/__properties__/")){
            return "android_property_area";
        }
        if(str.startsWith("/proc/")){
            return "procfs_process_state";
        }
        if(str.startsWith("/sys/")){
            return "sysfs_kernel_state";
        }
        if(str.startsWith("/apex/")){
            return "apex_runtime_asset";
        }
        if(str.startsWith("/dev/")){
            return "device_or_ipc_node";
        }
        return "other";
    }
    private static Map<String,Object> parseLine(String line){
        Matcher m = LINE_RE.matcher(line);
        if(!m.matches()){
            return null;
        }
        Map<String, Object> dict = new TreeMap<>();
        dict.put("schema_version", "memo.ebpf.traceprint.v1");
        dict.put("timestamp_s", Double.valueOf(m.group("ts")));
        dict.put("cpu", new BigInteger(m.group("cpu")));
        dict.put("trace_task", m.group("task").strip());
        dict.put("trace_tid", new BigInteger(m.group("tid")));
        dict.put("event_type", m.group("msg"));
        Matcher m0=KV_RE.matcher(m.group("rest"));
        while(m0.find()){
            String key = m0.group(1);
            Object value = m0.group(2).strip();
            if(SPEC_KEYS.contains(key)){
                try {
                    value = new BigInteger((String) value);
                }catch(NumberFormatException e){}//Do nothing
            }
            dict.put(key,value);
        }
        if(dict.containsKey("path")){
            dict.put("evidence_category",evidenceCategory((String)dict.get("path")));
        }
        return dict;
    }
    public static void convert(String input, String output, String charset){
        BufferedReader br=null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(input), charset));
        }catch (FileNotFoundException e){
            return;
        }catch (UnsupportedEncodingException e){
            try {
                br = new BufferedReader(new FileReader(input));
            } catch (FileNotFoundException ex){}// It won't happen
        }
        BufferedWriter bw;
        try {
            bw=new BufferedWriter(new FileWriter(output));
        } catch (IOException e) {
            try {
                br.close();
            } catch (IOException ex) {}//Ignore
            return;
        }
        JSONWriter writer=new JSONWriter(bw);
        try {
            String str = br.readLine();
            while(str!=null){
                Map<String,Object> map=parseLine(str);
                if(map!=null){
                    writer.writeObject(map);
                    bw.newLine();
                }
                str=br.readLine();
            }
        }catch (IOException e){}finally{
            try {
                br.close();
            } catch (IOException e) {}
            try {
                writer.close();
            } catch (IOException e) {}
        }
    }
}
