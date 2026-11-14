"""Exercise the API 102 package-ready lifecycle against the real module entry."""
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
public final class Log {
 public static final int INFO=4;
 public static final int WARN=5;
 public static final int ERROR=6;
 public static int i(String tag,String message){return 0;}
 public static int w(String tag,String message){return 0;}
 public static int w(String tag,String message,Throwable throwable){return 0;}
}
""",
    "io/github/libxposed/api/XposedModule.java": """
package io.github.libxposed.api;
import java.lang.reflect.*;
import java.util.*;
public abstract class XposedModule {
 static final Map<Executable,Hooker> hooks=new LinkedHashMap<>();
 public static final int PRIORITY_HIGHEST=Integer.MAX_VALUE;
 public interface ModuleLoadedParam {
  String getProcessName();
 }
 public interface PackageReadyParam {
  String getPackageName();
  boolean isFirstPackage();
  ClassLoader getClassLoader();
 }
 public interface HotReloadingParam {}
 public interface Hooker {
  Object intercept(Chain chain) throws Throwable;
 }
 public interface HookHandle {
  void unhook();
 }
 public interface HookBuilder {
  HookBuilder setPriority(int priority);
  HookBuilder setExceptionMode(ExceptionMode mode);
  HookBuilder setId(String id);
  HookHandle intercept(Hooker hooker);
 }
 public enum ExceptionMode {DEFAULT,PROTECTIVE,PASSTHROUGH}
 public interface Chain {
  Executable getExecutable();
  Object getThisObject();
  List<Object> getArgs();
  Object getArg(int index);
  Object proceed() throws Throwable;
  Object proceed(Object[] args) throws Throwable;
 }
 public int getApiVersion(){return 102;}
 public void onModuleLoaded(ModuleLoadedParam param){}
 public void onPackageReady(PackageReadyParam param){}
 public boolean onHotReloading(HotReloadingParam param){return false;}
 public void log(int priority,String tag,String message){}
 public void log(int priority,String tag,String message,Throwable throwable){}
 public HookBuilder hook(Executable origin){
  return new HookBuilder(){
   public HookBuilder setPriority(int priority){return this;}
   public HookBuilder setExceptionMode(ExceptionMode mode){return this;}
   public HookBuilder setId(String id){return this;}
   public HookHandle intercept(Hooker hooker){
    hooks.put(origin,hooker);
    return new HookHandle(){
     public void unhook(){hooks.remove(origin);}
    };
   }
  };
 }
 public static Object call(Object target,String name,Class<?>[] types,Object...args)
   throws Throwable {
  Method method=target.getClass().getMethod(name,types);
  Hooker hooker=hooks.get(method);
  if(hooker==null)return invoke(method,target,args);
  return hooker.intercept(new TestChain(method,target,args));
 }
 static Object invoke(Method method,Object target,Object[] args) throws Throwable {
  method.setAccessible(true);
  try {
   return method.invoke(target,args);
  }catch(InvocationTargetException exception){
   throw exception.getCause();
  }
 }
 static final class TestChain implements Chain {
  final Method method;
  final Object target;
  final Object[] args;
  TestChain(Method method,Object target,Object[] args){
   this.method=method;this.target=target;this.args=args;
  }
  public Executable getExecutable(){return method;}
  public Object getThisObject(){return target;}
  public List<Object> getArgs(){return Collections.unmodifiableList(Arrays.asList(args));}
  public Object getArg(int index){return args[index];}
  public Object proceed() throws Throwable {return invoke(method,target,args);}
  public Object proceed(Object[] values) throws Throwable {
   return invoke(method,target,values);
  }
 }
}
""",
    "LoaderLifecycleTest.java": """
import java.util.*;
import io.github.libxposed.api.XposedModule;
import io.github.saycm.huya.skipad.HuyaCompatEntry;
public class LoaderLifecycleTest {
 static final class Recorder extends ClassLoader {
  final List<String> requests=new ArrayList<>();
  public Class<?> loadClass(String name) throws ClassNotFoundException {
   requests.add(name);
   throw new ClassNotFoundException(name);
  }
 }
 static final class Param implements XposedModule.PackageReadyParam {
  final String name;
  final ClassLoader loader;
  Param(String name,ClassLoader loader){this.name=name;this.loader=loader;}
  public String getPackageName(){return name;}
  public boolean isFirstPackage(){return true;}
  public ClassLoader getClassLoader(){return loader;}
 }
 public static void main(String[] args) {
  HuyaCompatEntry entry=new HuyaCompatEntry();
  Recorder other=new Recorder();
  entry.onPackageReady(new Param("com.example.unrelated",other));
  if(!other.requests.isEmpty())
   throw new AssertionError("Unrelated package initialized hooks: "+other.requests);

  Recorder target=new Recorder();
  entry.onPackageReady(new Param("com.duowan.kiwi",target));
  for(String name:new String[]{
   "com.huya.kiwi.hyext.impl.modules.internal.HyExtAd",
   "com.duowan.kiwi.ad.helper.AmsAdHelper",
   "com.duowan.kiwi.adsplash.view.AdSplashFragment"})
   if(!target.requests.contains(name))
    throw new AssertionError("Package-ready loader was not used for "+name);

  int count=target.requests.size();
  entry.onPackageReady(new Param("com.duowan.kiwi",target));
  if(target.requests.size()!=count)
   throw new AssertionError("Hooks installed twice for the same package");
  if(entry.getApiVersion()!=102)
   throw new AssertionError("Expected libxposed API 102");
  if(entry.onHotReloading(null))
   throw new AssertionError("Unimplemented hot reload must be rejected");
  System.out.println("PASS: API 102 package-ready lifecycle and duplicate guard");
 }
}
""",
}


class HuyaLoaderLifecycleTest(unittest.TestCase):
    def test_package_ready_uses_the_api_102_loader_once(self):
        with tempfile.TemporaryDirectory(prefix="huya-loader-") as folder:
            root = Path(folder)
            sources = []
            for name, content in SOURCES.items():
                file = root / name
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_text(content, encoding="utf-8")
                sources.append(str(file))
            sources.append(
                str(
                    ROOT
                    / "tools/huya-compat-src/io/github/saycm/huya/skipad/HuyaCompatEntry.java"
                )
            )
            android = SDK / "platforms/android-36/android.jar"
            compile_result = subprocess.run(
                [
                    str(JDK / "bin" / ("javac" + EXE)),
                    "-encoding",
                    "UTF-8",
                    "-cp",
                    str(android),
                    "-d",
                    str(root / "classes"),
                    *sources,
                ],
                capture_output=True,
                text=True,
            )
            self.assertEqual(
                compile_result.returncode,
                0,
                compile_result.stdout + compile_result.stderr,
            )
            result = subprocess.run(
                [
                    str(JDK / "bin" / ("java" + EXE)),
                    "-cp",
                    os.pathsep.join([str(root / "classes"), str(android)]),
                    "LoaderLifecycleTest",
                ],
                capture_output=True,
                text=True,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            print(result.stdout.strip())


if __name__ == "__main__":
    unittest.main()
