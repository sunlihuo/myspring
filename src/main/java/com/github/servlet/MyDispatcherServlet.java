package com.github.servlet;

import com.github.annotation.*;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MyDispatcherServlet extends HttpServlet {
    private Properties p = new Properties();

    private List<String> classNames = new ArrayList<>();

    private Map<String, Object> ioc = new HashMap<>();

    private List<Handler> handlerMapping = new ArrayList<>();

    private AtomicBoolean isInit = new AtomicBoolean(false);

    @Override
    public void init(ServletConfig config) throws ServletException {
        if (isInit.get()){return;}
        isInit.set(true);

        System.out.println("1.加载配置文件");
        doLoadConfig(config.getInitParameter("contextConfigLocation"));

        System.out.println("2.扫描所有的类");
        doScanner(p.getProperty("scanPackage"));

        System.out.println("3.初始化所有的相关的类，并且将其保存到IOC容器");
        doInstance();

        System.out.println("4.依赖注入");
        doAutowrited();

        System.out.println("5.初始化HandlerMapping");
        initHandlerMapping();

        System.out.println("---------*-*-*-*-------------");

    }

    private void doLoadConfig(String location) {
        InputStream is = this.getClass().getClassLoader().getResourceAsStream(location);
        try {
            p.load(is);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (null != is) {
                try {
                    is.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

    }


    private void doScanner(String packageName) {
        String url = this.getClass().getClassLoader().getResource("/" + packageName.replaceAll("\\.", "/")).getFile();
        File dir = new File(url);
        for (File file : dir.listFiles()) {
            if (file.isDirectory()) {
                doScanner(packageName + "." + file.getName());
            } else {
                String className = (packageName + "." + file.getName()).replaceAll(".class","");
                classNames.add(className);
            }
        }

    }

    private void doInstance() {
        if (classNames.isEmpty()) {
            return;
        }

        try {
            for (String className : classNames) {
                Class<?> clazz = Class.forName(className);
                //IOC容器并不是一个简单的list
                //是一个String,Object>
                //beanName id


                //controller
                //1.默认是类名字母小写
                if (clazz.isAnnotationPresent(MyController.class)) {
                    String beanName = lowerFirst(clazz.getSimpleName());
                    ioc.put(beanName, clazz.newInstance());
                }
                //针对service
                //1.默认是类名字母小写
                //2.自定义类名
                //3.接口的类型为key存到IOC容器中
                else if (clazz.isAnnotationPresent(MyService.class)) {

                    MyService service = clazz.getAnnotation(MyService.class);
                    String beanName = service.value();
                    if ("".equals(beanName)) {
                        beanName = lowerFirst(clazz.getSimpleName());
                    }

                    Object instance = clazz.newInstance();
                    ioc.put(beanName, instance);

                    Class<?>[] interfaces = clazz.getInterfaces();
                    for (Class<?> i : interfaces) {
                        ioc.put(i.getName(), instance);
                    }

                } else {
                    continue;
                }

            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void doAutowrited() {
        if (ioc.isEmpty()) {
            return;
        }

        for (Map.Entry<String, Object> entry : ioc.entrySet()) {
            Field[] fields = entry.getValue().getClass().getDeclaredFields();
            //没有注解 的不用DI
            for (Field field : fields) {
                if (!field.isAnnotationPresent(MyAutowrited.class)) {
                    continue;
                }

                MyAutowrited autowrited = field.getAnnotation(MyAutowrited.class);
                String beanName = autowrited.value();
                if ("".equals(beanName)) {
                    beanName = field.getType().getName();
                }
                //不管是不是私有属性
                //暴力访问
                field.setAccessible(true);
                try {
                    field.set(entry.getValue(), ioc.get(beanName));
                } catch (Exception e) {
                    e.printStackTrace();
                }

            }

        }

    }

    private void initHandlerMapping() {
        if (ioc.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : ioc.entrySet()) {
            Class<?> clazz = entry.getValue().getClass();

            if (!clazz.isAnnotationPresent(MyController.class)) {
                continue;
            }

            String url = "";
            if (clazz.isAnnotationPresent(MyRequestMapping.class)) {
                MyRequestMapping requestMapping = clazz.getAnnotation(MyRequestMapping.class);
                url = requestMapping.value();
            }

            Method[] methods = clazz.getMethods();
            for (Method method : methods) {
               if (!method.isAnnotationPresent(MyRequestMapping.class)){continue;}

                MyRequestMapping requestMapping = method.getAnnotation(MyRequestMapping.class);
                String regex = ("/" + url + "/" + requestMapping.value()).replaceAll("/+", "/");
                Pattern pattern = Pattern.compile(regex);

                handlerMapping.add(new Handler(pattern, entry.getValue(), method));
                System.out.println("Add Mapping : " + regex + ","+method);

            }

        }


    }

    //6.等待调用
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        doDispatch(req, resp);
    }

    private void doDispatch(HttpServletRequest req, HttpServletResponse resp) {
        if (handlerMapping.isEmpty()) {return;}

        Handler handler = getHandler(req);
        if (null == handler) {
            try {
                resp.getWriter().write("404 NOT Found");
            } catch (IOException e) {
                e.printStackTrace();
            }
            return ;
        }

        //获取方法的参数列表
        Class<?>[] paramTypes = handler.method.getParameterTypes();

        //保存方法 的参数列表
        Object[] paramValues = new Object[paramTypes.length];
        Map<String, String[]> params = req.getParameterMap();
        for (Map.Entry<String, String[]> param : params.entrySet()) {
            String value = Arrays.toString(param.getValue()).replaceAll("\\[|\\]", "").replaceAll(",\\s",",");

            //如果找到匹配对象，则开始填充参数值
            if (!handler.paramIndexMapping.containsKey(param.getKey())){continue;}
            int index = handler.paramIndexMapping.get(param.getKey());
            paramValues[index] = value;
        }

        //设置方法中的request和response对象
        Integer reqIndex = handler.paramIndexMapping.get(HttpServletRequest.class.getName());
        if (reqIndex != null){
            paramValues[reqIndex] = req;
        }
        Integer respIndex = handler.paramIndexMapping.get(HttpServletResponse.class.getName());
        if (respIndex != null) {
            paramValues[respIndex] = resp;
        }


        try {
            handler.method.invoke(handler.controller, paramValues);
        } catch (Exception e) {
            e.printStackTrace();
        }


    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        doDispatch(req, resp);
    }

    private String lowerFirst(String str) {
        char[] chars = str.toCharArray();
        chars[0] += 32;
        return String.valueOf(chars);
    }

    private Handler getHandler(HttpServletRequest req) {
        if (handlerMapping.isEmpty()){return null;}

        String url = req.getRequestURI();
        String contextPath = req.getContextPath();
        url = url.replace(contextPath, "").replaceAll("/+","/");

        for (Handler handler : handlerMapping) {
            Matcher matcher = handler.pattern.matcher(url);
            if (!matcher.matches()){continue;}

            return handler;
        }
        return null;
    }

    private class Handler{
        protected Object controller;//保存方法对应的实例
        protected Method method;//保存映射的方法
        protected Pattern pattern;
        protected Map<String, Integer> paramIndexMapping;//参数顺序


        protected Handler(Pattern pattern, Object controller, Method method){
            this.pattern = pattern;
            this.controller = controller;
            this.method = method;
            paramIndexMapping = new HashMap<>();
            putParamIndexMapping(method);
        }

        private void putParamIndexMapping(Method method) {

            //提取方法中加了注解的参数
            Annotation[][] pa = method.getParameterAnnotations();
            for (int i = 0; i < pa.length; i++) {
                for (Annotation a : pa[i]) {
                    if (a instanceof MyRequestParam){
                        String paramName = ((MyRequestParam)a).value();
                        if (!"".equals(paramName.trim())){
                            paramIndexMapping.put(paramName, i);
                        }
                    }
                }
            }

            //提取方法中的request和response参数
            Class<?>[] paramsTypes = method.getParameterTypes();
            for (int i = 0; i < paramsTypes.length; i++) {
                Class<?> type = paramsTypes[i];
                if (type == HttpServletRequest.class ||
                        type == HttpServletResponse.class){
                    paramIndexMapping.put(type.getName(), i);
                }
            }
        }

    }
}
