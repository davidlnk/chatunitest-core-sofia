package zju.cst.aces.api.phase.solution;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import zju.cst.aces.api.config.Config;
import zju.cst.aces.api.impl.PromptConstructorImpl;
import zju.cst.aces.api.phase.PhaseImpl;
import zju.cst.aces.coverage.CodeCoverageAnalyzer;
import zju.cst.aces.dto.*;
import zju.cst.aces.parser.ProjectParser;
import zju.cst.aces.util.telpa.JavaParserUtil;


import java.io.IOException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class TELPA extends PhaseImpl {
    public static  MethodExampleMap methodExampleMap;
    public static  Map<String, String> forwardAnalysis;
    public static  Map<String, String> backwardAnalysis;
    public static  String counterExampleCode;
    public static  boolean isCovered=false;
    public static  int count=0;
    public TELPA(Config config) {
        super(config);
    }
    @Override
    public void prepare() {
        ProjectParser.config=config;
        JavaParserUtil javaParserUtil=new JavaParserUtil(config);
        JavaParserUtil.cus=javaParserUtil.getParseResult();
        Logger logger = Logger.getLogger("slicing.graphs");
        logger.setLevel(Level.SEVERE); // 只输出严重错误
        JavaParserUtil.cusWithTest=javaParserUtil.addFilesToCompilationUnits(config.counterExamplePath);
        MethodExampleMap methodExampleMap = javaParserUtil.createMethodExampleMap(javaParserUtil.cus);
        this.methodExampleMap = methodExampleMap;
        javaParserUtil.findBackwardAnalysis(methodExampleMap);
        javaParserUtil.exportMethodExampleMap(methodExampleMap);
        javaParserUtil.exportBackwardAnalysis(methodExampleMap);
        super.prepare();
    }

    @Override
    public PromptConstructorImpl generatePrompt(ClassInfo classInfo, MethodInfo methodInfo, int num) {
        // String
        if (config.getTmpOutput() != null) {
            // 获取前向分析结果
            Map<String, String> forwardAnalysis = null;
            Map<String, String>  backwardAnalysis=null;
            try {
                forwardAnalysis = getForwardAnalysis(classInfo, methodInfo);
                // 获取后向分析结果
                backwardAnalysis = getBackwardAnalysis(classInfo, methodInfo);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            this.forwardAnalysis=forwardAnalysis;
            this.backwardAnalysis=backwardAnalysis;
        }
        String counterExampleCode = getCounterExampleCode(classInfo,methodInfo);
        if(counterExampleCode!=null){
            this.counterExampleCode=counterExampleCode;
        }
        return super.generatePrompt(classInfo, methodInfo, num);
    }
    @Override
    public void generateTest(PromptConstructorImpl pc){

        if(isCovered){
            isCovered=false;
            config.getLogger().info("The test generated by the SBST has reached 100% "+count++);
            return;
        }
        super.generateTest(pc);
    }

    public String getCounterExampleCode(ClassInfo classInfo,MethodInfo methodInfo) {
        JavaParserUtil javaParserUtil=new JavaParserUtil(config);
        NodeList<CompilationUnit> parseResult = javaParserUtil.cusWithTest;
        //Inital the Coverage Relevant Variable
        StringBuilder counterExampleCode = new StringBuilder();
        List<Map<String, Object>> coverageResults = new ArrayList<>();
        Map<String, Object> bestCoverageInfo = null;
        List<String> selectedMethods = new ArrayList<>();
        Set<String> totalUncoveredLines = new HashSet<>();


        String targetMethodName = classInfo.getFullClassName() + "." + methodInfo.getMethodSignature();

        // 反向分析结果数据结构如下：
        Map<String, Set<List<MethodExampleMap.MEC>>> methodPaths = this.methodExampleMap.getMemList();

        if(methodPaths!=null&&methodPaths.containsKey(targetMethodName)) {
            for (List<MethodExampleMap.MEC> path : methodPaths.get(targetMethodName)) {
                MethodExampleMap.MEC topMethod = path.get(path.size() - 1);  // 获取路径的顶端方法
                Map<String, String> testMethodInfo = javaParserUtil.findCodeByMethodInfo(topMethod.getMethodName(), parseResult);
                try {
                    for (Map.Entry<String, String> testMethod : testMethodInfo.entrySet()) {
                        Map<String, Object> coverageInfo = new CodeCoverageAnalyzer().analyzeCoverage(
                                testMethod.getValue(), testMethod.getKey(),
                                classInfo.getFullClassName(),
                                methodInfo.getMethodSignature(),
                                config.project.getBuildPath().toString(),
                                config.project.getCompileSourceRoots().get(0),
                                config.classPaths
                        );
                    }
                } catch (Exception e) {
                    config.getLogger().error("Failed to analyze coverage for " + topMethod.getClassName());
                }
            }
        }
        // 按照覆盖率排序
        coverageResults.sort((a, b) -> {
            Double lineCoverageA = (Double) a.get("lineCoverage");
            Double lineCoverageB = (Double) b.get("lineCoverage");
            return Double.compare(lineCoverageB,lineCoverageA);
        });

        // 选择覆盖率最高的方法
        if (!coverageResults.isEmpty()) {
            bestCoverageInfo = coverageResults.get(0);
            //覆盖率达到了100.00%
            if(bestCoverageInfo.get("lineCoverage").equals(100.0)){
                isCovered=true;
                return null;
            }
            totalUncoveredLines.addAll((Collection<String>) bestCoverageInfo.get("uncoveredLines"));
            selectedMethods.add(bestCoverageInfo.get("methodCode").toString());
        }

        // 检查其他方法是否能减少未覆盖行
        for (int  i = 1; i < coverageResults.size(); i++) {
            Map<String, Object> coverageInfo = coverageResults.get(i);
            List<String> uncoveredLines = (List<String>) coverageInfo.get("uncoveredLines");
            boolean reducesUncoveredLines = false;

            for (String line : totalUncoveredLines) {
                if (!uncoveredLines.contains(line)) {
                    reducesUncoveredLines = true;
                    break;
                }
            }
            //取交集
            if (reducesUncoveredLines) {
                totalUncoveredLines.retainAll(uncoveredLines);
                selectedMethods.add((String) coverageInfo.get("methodCode"));
            }
        }

        // 构建返回结果
        counterExampleCode.append("these counter-examples enter the target method via the selected sequence of method invocations:\n");
        for (String method : selectedMethods) {
            counterExampleCode.append(method).append("\n");
        }

        return counterExampleCode.toString(); // 返回结果
    }


    public Map<String, String> getBackwardAnalysis(ClassInfo classInfo, MethodInfo methodInfo) throws IOException {
        Map<String, String> backwardAnalysis = new HashMap<>();
        MethodExampleMap methodExampleMap = this.methodExampleMap;
        String targetMethodName = classInfo.getFullClassName() + "." + methodInfo.getMethodSignature();

        // 直接获取 targetMethodName 对应的值
        Set<List<MethodExampleMap.MEC>> paths = methodExampleMap.getMemList().get(targetMethodName);
        if (paths != null) {
            StringBuilder info = new StringBuilder();
            for (List<MethodExampleMap.MEC> path : paths) {
                for (int i = path.size() - 1; i >= 0; i--) {
                    MethodExampleMap.MEC mec = path.get(i);
                    info.append(mec.getCode()).append(" -> ");
                }
                info.append(targetMethodName).append("\n");
            }

            if (info.length() > 0) {
                backwardAnalysis.put(targetMethodName, info.toString().trim());
            }
        }
        return backwardAnalysis;
    }


    public Map<String, String> getForwardAnalysis(ClassInfo classInfo, MethodInfo methodInfo) throws IOException {
        Map<String, String> forwardAnalysis = new HashMap<>();
        Map<String, TreeSet<OCM.OCC>> ocm = config.ocm.getOCM();
        String targetClassName = classInfo.getFullClassName();
        for (Map.Entry<String, TreeSet<OCM.OCC>> entry : ocm.entrySet()) {
            String key = entry.getKey();
            if (targetClassName.equals(key)) {
                TreeSet<OCM.OCC> occSet = entry.getValue();
                StringBuilder info = new StringBuilder();
                for (OCM.OCC occ : occSet) {
                    info.append(occ.getClassName()).append(".").append(occ.getMethodName())
                            .append(" at line ").append(occ.getLineNum()).append(" the construction code is ").append(occ.getCode()).append("\n");
                }

                if (info.length() > 0) {
                    forwardAnalysis.put(targetClassName, info.toString().trim());
                }
                break;
            }
        }
        return forwardAnalysis;
    }
}
