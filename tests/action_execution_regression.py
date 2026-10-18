#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""原样抽取生产 exec 的 scroll/key/open_api 分支；只替换 Android 动作边界。"""
from pathlib import Path
import importlib.util
import os
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("action_json_fixture", ROOT / "tests/asr_lan_regression.py")
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)
source = (ROOT / "agent-lite/src/com/dsh/agentlite/AgentEngine.java").read_text()
parts = []
for first, end in [('case "scroll": {', 'case "edge_back": {'), ('case "open_api": {', 'case "wifi": {')]:
    start = source.index(first, source.index('private String exec('))
    stop = source.index(end, start)
    parts.append(source[start:stop])
json_source = fixture.STUBS['org/json/JSONObject.java'].replace('    @Override public String toString()', '''
    public int getInt(String key) {
        Object value=values.get(key);
        if (!(value instanceof Number)) throw new IllegalArgumentException("missing/noninteger: " + key);
        return ((Number)value).intValue();
    }
    @Override public String toString()''')
program = r'''
package com.dsh.agentlite;
import org.json.JSONObject;
import java.net.URLEncoder;
import java.util.*;
public final class TestsActionExecution {
    static int checks;
    static void yes(boolean ok) { checks++; if (!ok) throw new AssertionError("assertion " + checks); }
    static JSONObject action(String kind) { return new JSONObject().put("action",kind); }
    static final class Metrics { int widthPixels=400, heightPixels=800; }
    static final class Resources { Metrics getDisplayMetrics(){return new Metrics();} }
    static final class App { Resources getResources(){return new Resources();} }
    static final class Service {
        int calls; int[] gesture; String global; boolean ok=true;
        boolean swipe(int x1,int y1,int x2,int y2,int ms){calls++;gesture=new int[]{x1,y1,x2,y2,ms};return ok;}
        boolean goHome(){calls++;global="home";return ok;}
        boolean goBack(){calls++;global="back";return ok;}
        boolean goRecents(){calls++;global="recents";return ok;}
    }
    static final class Uri {
        final String text; Uri(String text){this.text=text;}
        static Uri parse(String text){return new Uri(text);}
        static String encode(String text){return URLEncoder.encode(text,java.nio.charset.StandardCharsets.UTF_8).replace("+","%20");}
    }
    static final class Intent {
        static final String ACTION_VIEW="view", ACTION_DIAL="dial", ACTION_SENDTO="sms";
        final String action; final Uri uri; Map<String,String> extra=new HashMap<>();
        Intent(String a,Uri u){action=a;uri=u;}
        Intent putExtra(String k,String v){extra.put(k,v);return this;}
    }
    App app=new App(); Service svc=new Service(); String lastApp="prior-app";
    int launches, sleeps; boolean launchError; Intent launched;
    void startActivity(Intent intent) { launches++; if(launchError)throw new IllegalStateException("fixture launch failure"); launched=intent; }
    void sleep(int ms) { sleeps++; }
    String exec(JSONObject a) throws Exception {
        String kind=a.optString("action");
        switch(kind) {
''' + '\n'.join(parts) + r'''
            default: throw new Exception("unexpected action");
        }
    }
    static void fails(TestsActionExecution p,JSONObject a) throws Exception {
        try {p.exec(a);} catch(Exception expected){checks++;return;}
        throw new AssertionError("expected explicit failure");
    }
    public static void main(String[] ignored) throws Exception {
        int[][] coords={{200,600,200,200,400},{200,200,200,600,400},{100,400,300,400,400},{300,400,100,400,400}};
        String[] dirs={"down","up","left","right"};
        for(int i=0;i<dirs.length;i++) {
            TestsActionExecution p=new TestsActionExecution();
            yes(p.exec(action("scroll").put("direction",dirs[i])).equals("scroll "+dirs[i]));
            yes(Arrays.equals(coords[i],p.svc.gesture)); yes(p.svc.calls==1);
            p.svc.ok=false; fails(p,action("scroll").put("direction",dirs[i])); yes(p.svc.calls==2);
        }
        TestsActionExecution invalid=new TestsActionExecution();
        fails(invalid,action("scroll").put("direction","diagonal")); yes(invalid.svc.calls==0);
        for(String alias:new String[]{"home","back"}) {
            int code=alias.equals("home")?3:4;
            TestsActionExecution a=new TestsActionExecution(), b=new TestsActionExecution();
            yes(a.exec(action(alias)).equals(b.exec(action("key").put("code",code))));
            yes(a.svc.global.equals(b.svc.global)); yes(Objects.equals(a.lastApp,b.lastApp));
            a.lastApp="prior-app";a.svc.ok=false;fails(a,action(alias));yes(a.lastApp.equals("prior-app"));
            b.lastApp="prior-app";b.svc.ok=false;fails(b,action("key").put("code",code));yes(b.lastApp.equals("prior-app"));
        }
        TestsActionExecution recents=new TestsActionExecution(); recents.exec(action("key").put("code",187));yes(recents.svc.global.equals("recents"));
        fails(invalid,action("key"));fails(invalid,action("key").put("code",999));yes(invalid.svc.calls==0);
        for(JSONObject bad:new JSONObject[]{action("open_api").put("target","unknown"),action("open_api").put("target","京东").put("intent","navigate"),action("open_api").put("target","高德").put("intent","unknown")}) {
            TestsActionExecution p=new TestsActionExecution();fails(p,bad);yes(p.launches==0&&p.sleeps==0);
        }
        TestsActionExecution broken=new TestsActionExecution();broken.launchError=true;
        fails(broken,action("open_api").put("target","京东"));yes(broken.launches==1&&broken.sleeps==0);
        String query="引号\"与\\反斜杠";
        TestsActionExecution jd=new TestsActionExecution();jd.exec(action("open_api").put("target","京东").put("param",query));
        String payload=java.net.URLDecoder.decode(jd.launched.uri.text.split("params=",2)[1],java.nio.charset.StandardCharsets.UTF_8);
        yes(new JSONObject(payload).getString("keyWord").equals(query));yes(jd.launches==1);
        TestsActionExecution dial=new TestsActionExecution();dial.exec(action("open_api").put("target","高德").put("intent","dial").put("param","123"));
        yes(dial.launched.action.equals("dial")&&dial.launched.uri.text.equals("tel:123"));
        System.out.println("动作执行原样分支: "+checks+" 断言 / 0 失败；平台边界为桩，非真机验收");
    }
}
'''
with tempfile.TemporaryDirectory(prefix='action_exec_') as tmp:
    base=Path(tmp).resolve()
    assert base.name.startswith('action_exec_') and base != ROOT
    sources=[]
    for name in ['JSONObject.java','JSONArray.java','JsonCodec.java']:
        dest=base/'org/json'/name;dest.parent.mkdir(parents=True,exist_ok=True)
        dest.write_text(json_source if name=='JSONObject.java' else fixture.STUBS['org/json/'+name])
        sources.append(str(dest))
    dest=base/'TestsActionExecution.java';dest.write_text(program);sources.append(str(dest))
    subprocess.run(['javac','--release',os.environ.get('JAVA_RELEASE','11'),'-encoding','UTF-8','-d',str(base),*sources],check=True)
    subprocess.run(['java','-cp',str(base),'com.dsh.agentlite.TestsActionExecution'],check=True)
