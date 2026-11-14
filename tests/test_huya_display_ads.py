"""Exercise the real hooks against the display-ad interfaces found in Huya."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from test_huya_loader_lifecycle import JDK, ROOT, SDK, SOURCES


DISPLAY_SOURCES = dict(SOURCES)
DISPLAY_SOURCES.update({
    "com/duowan/kiwi/adsplash/controller/AdRouter.java": """
package com.duowan.kiwi.adsplash.controller;
public class AdRouter {
 public int originalCalls;
 public int isNeedShowAdSplash(android.content.Context c,boolean cache,boolean hot,boolean preload){
  originalCalls++;return hot?8:0;
 }
}
""",
    "com/duowan/kiwi/ad/impl/HyAdModule.java": """
package com.duowan.kiwi.ad.impl;
public class HyAdModule {
 public boolean isNeedShieldAdOutLivingRoom(){return false;}
 public boolean isNeedShieldAdInLivingRoom(){return false;}
}
""",
    "com/duowan/kiwi/list/patface/chain/PatFaceInterceptorChain.java": """
package com.duowan.kiwi.list.patface.chain;
public class PatFaceInterceptorChain {
 public int queuedDialogs;
 public boolean proceed(int location){queuedDialogs++;return true;}
}
""",
    "com/duowan/kiwi/listframe/component/LineItem.java": """
package com.duowan.kiwi.listframe.component;
public class LineItem {
 private final Object mViewObject;
 public LineItem(Object value){mViewObject=value;}
 public Object getLineItem(){return mViewObject;}
}
""",
    "com/duowan/kiwi/list/component/RecommendAdComponent.java": """
package com.duowan.kiwi.list.component;
public class RecommendAdComponent {public static class ViewObject {}}
""",
    "DisplayAdsTest.java": """
import java.util.*;
import io.github.libxposed.api.XposedModule;
import io.github.saycm.huya.skipad.HuyaCompatEntry;
import com.duowan.kiwi.adsplash.controller.AdRouter;
import com.duowan.kiwi.ad.impl.HyAdModule;
import com.duowan.kiwi.list.patface.chain.PatFaceInterceptorChain;
import com.duowan.kiwi.listframe.component.LineItem;
import com.duowan.kiwi.list.component.RecommendAdComponent;
public class DisplayAdsTest {
 static final class Param implements XposedModule.PackageReadyParam {
  final ClassLoader loader;
  Param(ClassLoader loader){this.loader=loader;}
  public String getPackageName(){return "com.duowan.kiwi";}
  public boolean isFirstPackage(){return true;}
  public ClassLoader getClassLoader(){return loader;}
 }
 public static class Card {
  public int iCardType;
  public List<Object> vAdCard=new ArrayList<>();
 }
 public static class User {
  public Card tMCard;
  public String sTitle="normal live";
 }
 public static class Grid {
  public User userRecItem;
 }
 public static class Lizard {
  private Object mLiveListAdInfo;
  public Lizard(Object ad){mLiveListAdInfo=ad;}
 }
 static LineItem nativeAd(){
  Grid grid=new Grid();grid.userRecItem=new User();grid.userRecItem.tMCard=new Card();
  grid.userRecItem.tMCard.iCardType=4;
  grid.userRecItem.tMCard.vAdCard.add(new Object());
  return new LineItem(grid);
 }
 static void check(boolean condition,String message){
  if(!condition)throw new AssertionError(message);
 }
 static Object call(Object target,String method,Class<?>[] types,Object... args) throws Throwable{
  return XposedModule.call(target,method,types,args);
 }
 public static void main(String[] args) throws Throwable {
  new HuyaCompatEntry().onPackageReady(
   new Param(DisplayAdsTest.class.getClassLoader()));
  if(args[0].equals("splash")){
   AdRouter router=new AdRouter();
   for(boolean hot:new boolean[]{false,true})
    for(boolean cached:new boolean[]{false,true}){
     Object result=call(router,"isNeedShowAdSplash",
      new Class<?>[]{android.content.Context.class,boolean.class,boolean.class,boolean.class},
      null,cached,hot,true);
     check(Integer.valueOf(6).equals(result),"Splash must take Huya's no-display route: "+result);
    }
   check(router.originalCalls==0,"Splash must be blocked before requesting or showing it");
  }else if(args[0].equals("promotion")){
   PatFaceInterceptorChain chain=new PatFaceInterceptorChain();
   for(int location:new int[]{0,1,2,3}){
    Object result=call(chain,"proceed",new Class<?>[]{int.class},location);
    check(Boolean.FALSE.equals(result),"Automatic promotion must return the no-display result");
   }
   check(chain.queuedDialogs==0,"Promotion must be stopped before adding a dialog to the queue");
  }else if(args[0].equals("batch")){
   Object adapter=Class.forName(args[1]).getConstructor().newInstance();
   LineItem first=new LineItem(new Object()),last=new LineItem(new Object());
   Grid ordinary=new Grid();ordinary.userRecItem=new User();ordinary.userRecItem.tMCard=new Card();
   ordinary.userRecItem.tMCard.iCardType=4;
   LineItem placeholder=new LineItem(ordinary);
   List<LineItem> input=Collections.unmodifiableList(Arrays.asList(
    first,new LineItem(new RecommendAdComponent.ViewObject()),nativeAd(),
    new LineItem(new Lizard(new Object())),placeholder,last));
   call(adapter,args[2],new Class<?>[]{List.class},input);
   List<?> items=(List<?>)adapter.getClass().getField("items").get(adapter);
   check(items.equals(Arrays.asList(first,placeholder,last)),
    "Ad cards must be removed while normal cards keep their order: "+items.size());
   check(input.size()==6,"Caller-owned lists must stay unchanged");
  }else if(args[0].equals("single")){
   Object adapter=Class.forName(args[1]).getConstructor().newInstance();
   Class<?>[] types={LineItem.class,int.class};
   call(adapter,args[2],types,nativeAd(),0);
   check(adapter.getClass().getField("calls").getInt(adapter)==0,
    "Single ad insertion must not insert or send an invalid item notification");
   LineItem normal=new LineItem(new Object());
   call(adapter,args[2],types,normal,0);
   check(adapter.getClass().getField("calls").getInt(adapter)==1,
    "Normal insertion must still run");
  }else if(args[0].equals("shield")){
   HyAdModule module=new HyAdModule();
   check(Boolean.TRUE.equals(call(module,"isNeedShieldAdOutLivingRoom",new Class<?>[0])),
    "Ordinary home, subscription, search and profile ad requests must be shielded");
   check(!module.isNeedShieldAdInLivingRoom(),
    "The live-room shield must stay unchanged because it also guards rewarded ads");
  }
  System.out.println("PASS: "+String.join(" ",args));
 }
}
""",
})

for package, name, methods in (
    ("com.duowan.kiwi.listframe.adapter", "ListLineRecyclerViewAdapter",
     ("replace", "replaceAndNotify", "insert", "insertAndNotify")),
    ("com.duowan.kiwi.listline", "ListLineAdapter",
     ("replaceData", "replaceAndNotify", "insertItemDataOnly", "insertItemAndNotify")),
):
    DISPLAY_SOURCES[package.replace(".", "/") + "/" + name + ".java"] = f"""
package {package};
import java.util.*;
import com.duowan.kiwi.listframe.component.LineItem;
public class {name} {{
 public List<LineItem> items=new ArrayList<>();
 public int calls;
 public void {methods[0]}(List<LineItem> list){{calls++;items=new ArrayList<>(list);}}
 public void {methods[1]}(List<LineItem> list){{calls++;items=new ArrayList<>(list);}}
 public void {methods[2]}(LineItem item,int index){{calls++;items.add(index,item);}}
 public void {methods[3]}(LineItem item,int index){{calls++;items.add(index,item);}}
}}
"""


class HuyaDisplayAdsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.folder = tempfile.TemporaryDirectory(prefix="huya-display-")
        cls.root = Path(cls.folder.name)
        sources = []
        for name, content in DISPLAY_SOURCES.items():
            if name == "LoaderLifecycleTest.java":
                continue
            path = cls.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
            sources.append(str(path))
        sources.append(str(ROOT / "tools/huya-compat-src/io/github/saycm/huya/skipad/HuyaCompatEntry.java"))
        cls.classpath = os.pathsep.join([
            str(cls.root / "classes"), str(SDK / "platforms/android-36/android.jar")
        ])
        result = subprocess.run([
            str(JDK / "bin/javac.exe"), "-encoding", "UTF-8", "-cp", cls.classpath,
            "-d", str(cls.root / "classes"), *sources,
        ], capture_output=True, text=True)
        if result.returncode:
            cls.folder.cleanup()
            raise RuntimeError(result.stdout + result.stderr)

    @classmethod
    def tearDownClass(cls):
        cls.folder.cleanup()

    def run_case(self, *args):
        result = subprocess.run([
            str(JDK / "bin/java.exe"), "-cp", self.classpath, "DisplayAdsTest", *args,
        ], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_cold_and_hot_splash_use_the_no_display_route(self):
        self.run_case("splash")

    def test_automatic_promotion_stops_before_queuing_a_dialog(self):
        self.run_case("promotion")

    def test_both_list_adapters_filter_ads_without_changing_the_input(self):
        for adapter, methods in (
            ("com.duowan.kiwi.listframe.adapter.ListLineRecyclerViewAdapter",
             ("replace", "replaceAndNotify")),
            ("com.duowan.kiwi.listline.ListLineAdapter",
             ("replaceData", "replaceAndNotify")),
        ):
            for method in methods:
                with self.subTest(adapter=adapter, method=method):
                    self.run_case("batch", adapter, method)

    def test_single_ad_insertion_does_not_send_item_notifications(self):
        for adapter, methods in (
            ("com.duowan.kiwi.listframe.adapter.ListLineRecyclerViewAdapter",
             ("insert", "insertAndNotify")),
            ("com.duowan.kiwi.listline.ListLineAdapter",
             ("insertItemDataOnly", "insertItemAndNotify")),
        ):
            for method in methods:
                with self.subTest(adapter=adapter, method=method):
                    self.run_case("single", adapter, method)

    def test_ordinary_ad_shield_does_not_enable_the_reward_ad_shield(self):
        self.run_case("shield")


if __name__ == "__main__":
    unittest.main()
