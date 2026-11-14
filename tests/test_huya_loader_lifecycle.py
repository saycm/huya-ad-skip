"""Run the real entry against two loaders to reproduce Tinker's loader switch."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SDK = Path(os.environ["ANDROID_HOME"])
JDK = Path(os.environ["JAVA_HOME"])
EXE = ".exe" if os.name == "nt" else ""

SOURCES = {
    "android/util/Log.java": """
package android.util;
public class Log {
 public static int i(String t,String m){return 0;}
 public static int w(String t,String m){return 0;}
 public static int w(String t,String m,Throwable e){return 0;}
}
""",
    "android/os/Process.java": """
package android.os;
public class Process {public static int myPid(){return 1;}}
""",
    "android/app/Application.java": """
package android.app;
public class Application {
 private final ClassLoader loader;
 public Application(ClassLoader loader){this.loader=loader;}
 public ClassLoader getClassLoader(){return loader;}
}
""",
    "android/app/Instrumentation.java": """
package android.app;
public class Instrumentation {public void callApplicationOnCreate(Application app){}}
""",
    "de/robv/android/xposed/XposedBridge.java": """
package de.robv.android.xposed;
import java.lang.reflect.Member;
import java.util.*;
public class XposedBridge {
 static final Map<Member,XC_MethodHook> hooks=new LinkedHashMap<>();
 public static XC_MethodHook.Unhook hookMethod(Member m,XC_MethodHook h){
  hooks.put(m,h);return null;
 }
 public static void log(String s){}
 public static void log(Throwable t){}
 public static Object invokeOriginalMethod(Member m,Object o,Object[] a){return null;}
 public static void applicationCreated(android.app.Application app) throws Throwable {
  for(Map.Entry<Member,XC_MethodHook> entry:new ArrayList<>(hooks.entrySet())){
   if(!entry.getKey().getName().equals("callApplicationOnCreate"))continue;
   XC_MethodHook.MethodHookParam p=new XC_MethodHook.MethodHookParam();
   p.args=new Object[]{app};p.method=entry.getKey();
   entry.getValue().beforeHookedMethod(p);
   entry.getValue().afterHookedMethod(p);
  }
 }
}
""",
    "LoaderLifecycleTest.java": """
import java.util.*;
import android.app.Application;
import io.github.saycm.huya.skipad.HuyaCompatEntry;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
public class LoaderLifecycleTest {
 static class Recorder extends ClassLoader {
  final List<String> requests=new ArrayList<>();
  public Class<?> loadClass(String name) throws ClassNotFoundException{
   requests.add(name);throw new ClassNotFoundException(name);
  }
 }
 public static void main(String[] args) throws Throwable {
  Recorder early=new Recorder(), patched=new Recorder();
  XC_LoadPackage.LoadPackageParam p=new XC_LoadPackage.LoadPackageParam();
  p.packageName="com.duowan.kiwi";p.processName=p.packageName;p.classLoader=early;
  new HuyaCompatEntry().handleLoadPackage(p);
  if(!early.requests.isEmpty())
   throw new AssertionError("Ad classes loaded before application loader switch: "+early.requests);
  Application app=new Application(patched);
  XposedBridge.applicationCreated(app);
  for(String name:new String[]{
   "com.huya.kiwi.hyext.impl.modules.internal.HyExtAd",
   "com.duowan.kiwi.ad.helper.AmsAdHelper",
   "com.duowan.kiwi.adsplash.view.AdSplashFragment"})
   if(!patched.requests.contains(name))
    throw new AssertionError("Actual application loader was not used for "+name);
  int count=patched.requests.size();
  XposedBridge.applicationCreated(app);
  if(patched.requests.size()!=count)
   throw new AssertionError("Hooks installed twice for the same application");
  System.out.println("PASS: delayed loading, actual loader, duplicate initialization");
 }
}
""",
}


class HuyaLoaderLifecycleTest(unittest.TestCase):
    def test_hooks_follow_the_application_loader_switch(self):
        with tempfile.TemporaryDirectory(prefix="huya-loader-") as folder:
            root = Path(folder)
            sources = []
            for name, content in SOURCES.items():
                file = root / name
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_text(content, encoding="utf-8")
                sources.append(str(file))
            stubs = ROOT / "tools/xposed-stubs"
            sources += [
                str(file) for file in stubs.rglob("*.java")
                if file.name != "XposedBridge.java"
            ]
            sources.append(str(ROOT / "tools/huya-compat-src/io/github/saycm/huya/skipad/HuyaCompatEntry.java"))
            android = SDK / "platforms/android-36/android.jar"
            subprocess.run([
                str(JDK / "bin" / ("javac" + EXE)), "-encoding", "UTF-8",
                "-cp", str(android), "-d", str(root / "classes"), *sources,
            ], check=True, capture_output=True, text=True)
            result = subprocess.run([
                str(JDK / "bin" / ("java" + EXE)), "-cp",
                os.pathsep.join([str(root / "classes"), str(android)]),
                "LoaderLifecycleTest",
            ], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            print(result.stdout.strip())


if __name__ == "__main__":
    unittest.main()
