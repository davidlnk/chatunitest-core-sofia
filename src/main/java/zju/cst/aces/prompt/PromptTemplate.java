package zju.cst.aces.prompt;


import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import org.mockito.internal.matchers.Find;
import zju.cst.aces.api.config.Config;
import zju.cst.aces.runner.AbstractRunner;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class PromptTemplate {

    public static final String CONFIG_FILE = "config.properties";
    public String TEMPLATE_NO_DEPS = "";
    public String TEMPLATE_DEPS = "";
    public String TEMPLATE_ERROR = "";
    public Map<String, Object> dataModel = new HashMap<>();
    public Config config;

    public PromptTemplate(Config config) {
        this.config = config;
    }

    public void readProperties() throws IOException {
        Properties properties = new Properties();
        InputStream inputStream = PromptTemplate.class.getClassLoader().getResourceAsStream(CONFIG_FILE);
        properties.load(inputStream);
        TEMPLATE_NO_DEPS = properties.getProperty("PROMPT_TEMPLATE_NO_DEPS");//p1.ftl
        TEMPLATE_DEPS = properties.getProperty("PROMPT_TEMPLATE_DEPS");//p2.ftl
        TEMPLATE_ERROR = properties.getProperty("PROMPT_TEMPLATE_ERROR");//error.ftl
    }

    //渲染
    public String renderTemplate(String templateFileName) throws IOException, TemplateException{
        Configuration configuration = new Configuration(Configuration.VERSION_2_3_30);

//        if (config.getPromptPath() == null) {
//            // 使用类加载器获取插件自身的resources目录下的文件
//            configuration.setClassForTemplateLoading(PromptTemplate.class, "/prompt");
//        } else {
//            configuration.setDirectoryForTemplateLoading(config.getPromptPath().toFile());
//        }

        configuration.setClassForTemplateLoading(PromptTemplate.class, "/prompt");
        configuration.setDefaultEncoding("utf-8");
        Template template = configuration.getTemplate(templateFileName);

        Pattern pattern = Pattern.compile("\\$\\{(\\w+)\\}");
        Matcher matcher = pattern.matcher(template.toString());
        List<String> matches = new ArrayList<>();
        while (matcher.find()) {
            matches.add(matcher.group(1)); // 添加第一个捕获组的匹配
        }

        String generatedText;
        do {
            StringWriter writer = new StringWriter();
            template.process(dataModel, writer);
            generatedText = writer.toString();
            if (matches.size() > 0) {
                String key = matches.get(0);
                if (dataModel.containsKey(key)) {
                    dataModel.put(key, new ArrayList<String>());
                    matches.remove(matches.size()-1);
                }
            }
        } while (AbstractRunner.isExceedMaxTokens(config, generatedText));
        return generatedText;
    }
}
