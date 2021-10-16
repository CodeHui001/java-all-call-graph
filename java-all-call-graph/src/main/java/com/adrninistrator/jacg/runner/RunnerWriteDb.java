package com.adrninistrator.jacg.runner;

import com.adrninistrator.jacg.common.Constants;
import com.adrninistrator.jacg.common.DC;
import com.adrninistrator.jacg.dto.MethodCallEntity;
import com.adrninistrator.jacg.runner.base.AbstractRunner;
import com.adrninistrator.jacg.util.CommonUtil;
import com.adrninistrator.jacg.util.FileUtil;
import com.adrninistrator.jacg.util.SqlUtil;
import com.adrninistrator.javacg.extension.dto.CustomData;
import com.adrninistrator.javacg.extension.interfaces.CustomHandlerInterface;
import com.adrninistrator.javacg.stat.JCallGraph;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * @author adrninistrator
 * @date 2021/6/17
 * @description: 将通过java-callgraph生成的直接调用关系文件写入数据库
 */

public class RunnerWriteDb extends AbstractRunner {

    private static final Logger logger = LoggerFactory.getLogger(RunnerWriteDb.class);

    // 当类名为以下前缀时，才处理
    private Set<String> allowedClassPrefixSet;

    // 已写入数据库的完整类名
    private Map<String, Boolean> fullClassNameMap = new HashMap<>(Constants.BATCH_SIZE);

    // 记录完整类名
    private List<String> fullClassNameList = new ArrayList<>(Constants.BATCH_SIZE);

    // 类名相同但包名不同的类名
    private Set<String> duplicateClassNameSet = new HashSet<>();

    // 记录方法注解
    private List<Pair<String, String>> methodAnnotationList = new ArrayList<>(Constants.BATCH_SIZE);

    // 记录方法调用
    private List<MethodCallEntity> methodCallList = new ArrayList<>(Constants.BATCH_SIZE);

    // 记录Jar包信息
    private Map<Integer, String> jarInfoMap = new HashMap<>();

    // 记录自定义处理类
    private List<CustomHandlerInterface> customHandlerInterfaceList;

    // 记录是否读取到文件
    private boolean readFileFlag;

    // 记录是否有写数据库
    private boolean writeDbFlag;

    static {
        runner = new RunnerWriteDb();
    }

    @Override
    public boolean init() {
        if (confInfo.getDbDriverName().contains(Constants.MYSQL_FLAG) &&
                !confInfo.getDbUrl().contains(Constants.MYSQL_REWRITEBATCHEDSTATEMENTS)) {
            logger.info("使用MYSQL时，请在{}参数指定{}", Constants.KEY_DB_URL, Constants.MYSQL_REWRITEBATCHEDSTATEMENTS);
            return false;
        }

        // 使用多线程，线程数固定为10
        confInfo.setThreadNum(10);

        // 读取其他配置文件
        if (!readOtherConfig()) {
            return false;
        }
        return true;
    }

    @Override
    public void operate() {
        // 创建数据库表
        if (!createTables()) {
            return;
        }

        // 清理数据库表
        if (!truncateTables()) {
            return;
        }

        // 判断是否需要调用java-callgraph生成jar包的方法调用关系
        if (!callJavaCallGraph()) {
            someTaskFail = true;
            return;
        }

        // 读取通过java-callgraph生成的直接调用关系文件，处理类名与Jar包信息
        if (!handleClassCallAndJarInfo()) {
            return;
        }

        if (!readFileFlag) {
            if (confInfo.isInputIgnoreOtherPackage()) {
                logger.warn("未从文件读取到内容，请检查文件 {} ，以及配置文件指定的包名 {}", confInfo.getCallGraphInputFile(), Constants.FILE_IN_ALLOWED_CLASS_PREFIX);
            } else {
                logger.warn("未从文件读取到内容，请检查文件 {}", confInfo.getCallGraphInputFile());
            }
        }
        if (!writeDbFlag) {
            logger.warn("未向数据库写入数据，请检查文件内容 {}", confInfo.getCallGraphInputFile());
        }

        // 查找类名相同但包名不同的类
        if (!findDuplicateClass()) {
            return;
        }

        // 读取方法注解信息
        if (!handleMethodAnnotation()) {
            return;
        }

        // 创建线程
        createThreadPoolExecutor();

        // 读取通过java-callgraph生成的直接调用关系文件，处理方法调用
        if (!handleMethodCall()) {
            someTaskFail = true;
        }

        // 等待直到任务执行完毕
        wait4TPEDone();

        runSuccess = true;
    }

    // 判断是否需要调用java-callgraph生成jar包的方法调用关系
    private boolean callJavaCallGraph() {
        if (StringUtils.isBlank(confInfo.getCallGraphJarList())) {
            return true;
        }

        logger.info("尝试调用java-callgraph生成jar包的方法调用关系 {}", confInfo.getCallGraphJarList());

        String[] array = confInfo.getCallGraphJarList().split(Constants.FLAG_SPACE);
        for (String jarName : array) {
            if (!FileUtil.isFileExists(jarName)) {
                logger.error("文件不存在或不是文件 {}", jarName);
                return false;
            }
        }

        System.setProperty(Constants.JAVA_CALL_GRAPH_FLAG_OUT_FILE, confInfo.getCallGraphInputFile());

        // 调用java-callgraph2
        JCallGraph jCallGraph = new JCallGraph();
        // 添加自定义处理类
        if (!addExtensionHandler(jCallGraph)) {
            return false;
        }

        boolean success = jCallGraph.run(array);
        if (!success) {
            logger.error("调用java-callgraph生成jar包的方法调用关系失败");
            return false;
        }

        // 处理自定义数据
        return handleExtensionData();
    }

    // 添加自定义处理类
    private boolean addExtensionHandler(JCallGraph jCallGraph) {
        String extensionFilePath = Constants.DIR_CONFIG + File.separator + Constants.FILE_EXTENSION;

        Set<String> extensionClasses = FileUtil.readFile2Set(extensionFilePath);
        if (CommonUtil.isCollectionEmpty(extensionClasses)) {
            logger.info("未指定自定义处理类，跳过 {}", extensionFilePath);
            return true;
        }

        customHandlerInterfaceList = new ArrayList<>(extensionClasses.size());

        try {
            for (String extensionClass : extensionClasses) {
                Class clazz = Class.forName(extensionClass);
                Object obj = clazz.newInstance();
                if (!(obj instanceof CustomHandlerInterface)) {
                    logger.error("指定的类 {} 不是 {} 的实现类", extensionClass, CustomHandlerInterface.class.getName());
                    return false;
                }

                CustomHandlerInterface customHandlerInterface = (CustomHandlerInterface) obj;
                customHandlerInterface.init();

                customHandlerInterfaceList.add(customHandlerInterface);
                jCallGraph.addCustomHandler(customHandlerInterface);
            }
        } catch (Exception e) {
            logger.error("error ", e);
            return false;
        }
        return true;
    }

    // 处理自定义数据
    private boolean handleExtensionData() {
        if (CommonUtil.isCollectionEmpty(customHandlerInterfaceList)) {
            return true;
        }

        List<Object[]> objectList = new ArrayList<>(Constants.BATCH_SIZE);
        for (CustomHandlerInterface customHandlerInterface : customHandlerInterfaceList) {
            List<CustomData> customDataList = customHandlerInterface.getCustomDataList();
            if (CommonUtil.isCollectionEmpty(customDataList)) {
                continue;
            }

            // 插入自定义数据
            logger.info("自定义数据 {}", customHandlerInterface.getClass().getName());
            if (!insertExtensionData(customDataList, objectList)) {
                logger.error("插入自定义数据失败 {}", customHandlerInterface.getClass().getName());
                return false;
            }
        }
        return true;
    }

    // 插入自定义数据
    private boolean insertExtensionData(List<CustomData> customDataList, List<Object[]> objectList) {
        String sql = sqlCacheMap.get(Constants.SQL_KEY_INSERT_EXTENSION_DATA);
        if (sql == null) {
            sql = genAndCacheInsertSql(Constants.SQL_KEY_INSERT_EXTENSION_DATA,
                    false,
                    Constants.TABLE_PREFIX_EXTENSION_DATA,
                    Constants.TABLE_COLUMNS_EXTENSION_DATA);
        }

        // 分批插入数据
        int customDataListSize = customDataList.size();
        int insertTimes = (customDataListSize + Constants.BATCH_SIZE - 1) / Constants.BATCH_SIZE;

        for (int i = 0; i < insertTimes; i++) {
            for (int j = 0; j < Constants.BATCH_SIZE; j++) {
                int seq = i * Constants.BATCH_SIZE + j;
                if (seq >= customDataListSize) {
                    break;
                }
                CustomData customData = customDataList.get(seq);

                Object[] object = new Object[]{customData.getCallId(), customData.getDataType(), customData.getDataValue()};
                objectList.add(object);
            }
            logger.info("写入数据库，自定义数据表 {}", objectList.size());
            boolean success = dbOperator.batchInsert(sql, objectList);
            objectList.clear();

            if (!success) {
                return false;
            }
        }

        return true;
    }

    // 创建数据库表
    private boolean createTables() {
        String sqlClassName = readCreateTableSql(Constants.DIR_SQL + File.separator + Constants.FILE_SQL_CLASS_NAME);
        String sqlMethodAnnotation = readCreateTableSql(Constants.DIR_SQL + File.separator + Constants.FILE_SQL_METHOD_ANNOTATION);
        String sqlMethodCall = readCreateTableSql(Constants.DIR_SQL + File.separator + Constants.FILE_SQL_METHOD_CALL);
        String jarInfo = readCreateTableSql(Constants.DIR_SQL + File.separator + Constants.FILE_SQL_JAR_INFO);
        String extensionData = readCreateTableSql(Constants.DIR_SQL + File.separator + Constants.FILE_SQL_EXTENSION_DATA);

        if (StringUtils.isAnyBlank(sqlClassName, sqlMethodAnnotation, sqlMethodCall, jarInfo, extensionData)) {
            return false;
        }

        if (!dbOperator.createTable(sqlClassName) ||
                !dbOperator.createTable(sqlMethodAnnotation) ||
                !dbOperator.createTable(sqlMethodCall) ||
                !dbOperator.createTable(jarInfo) ||
                !dbOperator.createTable(extensionData)) {
            return false;
        }

        return true;
    }

    private String readCreateTableSql(String sqlFilePath) {
        String sql = FileUtil.readFile2String(sqlFilePath);
        if (StringUtils.isBlank(sql)) {
            logger.error("文件内容为空 {}", sqlFilePath);
            return null;
        }

        sql = sql.replace(Constants.APPNAME_IN_SQL, confInfo.getAppName());

        logger.info("建表sql: {}", sql);
        return sql;
    }

    // 清理数据库表
    private boolean truncateTables() {
        if (!dbOperator.truncateTable(Constants.TABLE_PREFIX_CLASS_NAME + confInfo.getAppName()) ||
                !dbOperator.truncateTable(Constants.TABLE_PREFIX_METHOD_CALL + confInfo.getAppName()) ||
                !dbOperator.truncateTable(Constants.TABLE_PREFIX_METHOD_ANNOTATION + confInfo.getAppName()) ||
                !dbOperator.truncateTable(Constants.TABLE_PREFIX_JAR_INFO + confInfo.getAppName()) ||
                !dbOperator.truncateTable(Constants.TABLE_PREFIX_EXTENSION_DATA + confInfo.getAppName())) {
            return false;
        }
        return true;
    }

    // 读取其他配置文件
    private boolean readOtherConfig() {
        if (confInfo.isInputIgnoreOtherPackage()) {
            String allowedClassPrefixFile = Constants.DIR_CONFIG + File.separator + Constants.FILE_IN_ALLOWED_CLASS_PREFIX;
            allowedClassPrefixSet = FileUtil.readFile2Set(allowedClassPrefixFile);
            if (CommonUtil.isCollectionEmpty(allowedClassPrefixSet)) {
                logger.error("读取文件不存在或内容为空 {}", allowedClassPrefixFile);
                return false;
            }
        }
        return true;
    }

    // 读取通过java-callgraph生成的直接调用关系文件，处理类名与Jar包信息
    private boolean handleClassCallAndJarInfo() {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(confInfo.getCallGraphInputFile()), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (StringUtils.isBlank(line)) {
                    continue;
                }

                if (!readFileFlag) {
                    readFileFlag = true;
                }

                if (line.startsWith(Constants.FILE_KEY_CLASS_PREFIX)) {
                    // 处理一个类名
                    if (!handleOneClassCall(line)) {
                        return false;
                    }
                } else if (line.startsWith(Constants.FILE_KEY_JAR_INFO_PREFIX)) {
                    // 处理一个Jar包信息
                    handleOneJarInfo(line);
                }
            }

            // 结束前将剩余数据写入数据库
            if (!writeClassName2Db()) {
                return false;
            }

            // 将Jar包信息数据写入数据库
            writeJarInfo2Db();

            return true;
        } catch (Exception e) {
            logger.error("error ", e);
            return false;
        }
    }

    // 查找类名相同但包名不同的类
    private boolean findDuplicateClass() {
        String sql = sqlCacheMap.get(Constants.SQL_KEY_CN_QUERY_DUPLICATE_CLASS);
        if (sql == null) {
            sql = "select " + DC.CN_SIMPLE_NAME + " from " + Constants.TABLE_PREFIX_CLASS_NAME + confInfo.getAppName() +
                    " group by " + DC.CN_SIMPLE_NAME + " having count(" + DC.CN_SIMPLE_NAME + ") > 1";
            cacheSql(Constants.SQL_KEY_CN_QUERY_DUPLICATE_CLASS, sql);
        }

        List<Object> list = dbOperator.queryListOneColumn(sql, null);
        if (list == null) {
            return false;
        }

        if (!list.isEmpty()) {
            for (Object object : list) {
                duplicateClassNameSet.add((String) object);
            }
        }
        return true;
    }

    // 处理一个类名
    private boolean handleOneClassCall(String data) {
        int indexBlank = data.indexOf(Constants.FLAG_SPACE);

        String callerFullClassName = data.substring(Constants.FILE_KEY_PREFIX_LENGTH, indexBlank).trim();
        String calleeFullClassName = data.substring(indexBlank + 1).trim();

        logger.debug("[{}] [{}]", callerFullClassName, calleeFullClassName);

        return handleClassName(callerFullClassName) && handleClassName(calleeFullClassName);
    }

    private boolean handleClassName(String fullClassName) {
        // 根据类名前缀判断是否需要处理
        if (confInfo.isInputIgnoreOtherPackage() && !isAllowedClassPrefix(fullClassName)) {
            return true;
        }

        // 通过java-callgraph生成的直接类引用关系存在重复，进行去重
        if (fullClassNameMap.putIfAbsent(fullClassName, Boolean.TRUE) == null) {
            fullClassNameList.add(fullClassName);

            if (fullClassNameList.size() >= Constants.BATCH_SIZE) {
                if (!writeClassName2Db()) {
                    return false;
                }
            }
        }

        return true;
    }

    private boolean writeClassName2Db() {
        if (fullClassNameList.isEmpty()) {
            return true;
        }

        logger.info("写入数据库，保存类名信息表 {}", fullClassNameList.size());

        if (!writeDbFlag) {
            writeDbFlag = true;
        }

        String sql = sqlCacheMap.get(Constants.SQL_KEY_INSERT_CLASS_NAME);
        if (sql == null) {
            sql = genAndCacheInsertSql(Constants.SQL_KEY_INSERT_CLASS_NAME,
                    false,
                    Constants.TABLE_PREFIX_CLASS_NAME,
                    Constants.TABLE_COLUMNS_CLASS_NAME);
        }

        List<Object[]> objectList = new ArrayList<>(fullClassNameList.size());
        for (String fullClassName : fullClassNameList) {
            String simpleClassName = CommonUtil.getSimpleClassNameFromFull(fullClassName);

            Object[] object = new Object[]{fullClassName, simpleClassName};
            objectList.add(object);
        }

        boolean success = dbOperator.batchInsert(sql, objectList);
        fullClassNameList.clear();
        return success;
    }

    // 处理一个Jar包信息
    private void handleOneJarInfo(String line) {
        int indexSpace = line.indexOf(Constants.FLAG_SPACE);

        String jarNumStr = line.substring(Constants.FILE_KEY_PREFIX_LENGTH, indexSpace).trim();
        String jarPath = line.substring(indexSpace + 1).trim();

        jarInfoMap.put(Integer.valueOf(jarNumStr), jarPath);
    }

    // 将Jar包信息数据写入数据库
    private boolean writeJarInfo2Db() {
        if (jarInfoMap.isEmpty()) {
            logger.error("Jar包信息为空");
            return false;
        }

        logger.info("写入数据库，保存Jar包信息 {}", jarInfoMap.size());

        if (!writeDbFlag) {
            writeDbFlag = true;
        }

        String sql = sqlCacheMap.get(Constants.SQL_KEY_INSERT_JAR_INFO);
        if (sql == null) {
            sql = genAndCacheInsertSql(Constants.SQL_KEY_INSERT_JAR_INFO,
                    false,
                    Constants.TABLE_PREFIX_JAR_INFO,
                    Constants.TABLE_COLUMNS_JAR_INFO);
        }

        List<Object[]> objectList = new ArrayList<>(jarInfoMap.size());
        for (Map.Entry<Integer, String> jarInfoEntry : jarInfoMap.entrySet()) {
            Integer jarNum = jarInfoEntry.getKey();
            String jarPath = jarInfoEntry.getValue();
            if (!FileUtil.isFileExists(jarPath)) {
                logger.error("Jar包文件不存在: {}", jarPath);
                return false;
            }

            long lastModified = FileUtil.getFileLastModified(jarPath);
            String jarFileHash = FileUtil.getFileMd5(jarPath);

            Object[] object = new Object[]{jarNum, CommonUtil.genHashWithLen(jarPath), jarPath, String.valueOf(lastModified), jarFileHash};
            objectList.add(object);
        }

        return dbOperator.batchInsert(sql, objectList);
    }

    // 读取方法注解信息
    private boolean handleMethodAnnotation() {
        String annotationInfoFilePath = confInfo.getCallGraphInputFile() + Constants.FILE_IN_ANNOTATION_TAIL;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(annotationInfoFilePath), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (StringUtils.isBlank(line)) {
                    continue;
                }

                // 处理一个方法注解
                if (!handleOneMethodAnnotation(line)) {
                    return false;
                }
            }

            // 结束前将剩余数据写入数据库
            if (!writeMethodAnnotation2Db()) {
                return false;
            }

            return true;
        } catch (Exception e) {
            logger.error("error ", e);
            return false;
        }
    }

    // 处理一个方法注解
    private boolean handleOneMethodAnnotation(String data) {

        String[] array = data.split(Constants.FLAG_SPACE);
        String fullMethod = array[0];

        // 根据类名前缀判断是否需要处理
        if (confInfo.isInputIgnoreOtherPackage() && !isAllowedClassPrefix(fullMethod)) {
            return true;
        }

        String annotation = array[1];

        logger.debug("[{}] [{}]", fullMethod, annotation);

        Pair<String, String> pair = new ImmutablePair<>(fullMethod, annotation);
        methodAnnotationList.add(pair);

        if (methodAnnotationList.size() >= Constants.BATCH_SIZE) {
            if (!writeMethodAnnotation2Db()) {
                return false;
            }
        }

        return true;
    }

    private boolean writeMethodAnnotation2Db() {
        if (methodAnnotationList.isEmpty()) {
            return true;
        }

        logger.info("写入数据库，保存方法注解信息表 {}", methodAnnotationList.size());

        if (!writeDbFlag) {
            writeDbFlag = true;
        }

        String sql = sqlCacheMap.get(Constants.SQL_KEY_INSERT_METHOD_ANNOTATION);
        if (sql == null) {
            sql = genAndCacheInsertSql(Constants.SQL_KEY_INSERT_METHOD_ANNOTATION,
                    false,
                    Constants.TABLE_PREFIX_METHOD_ANNOTATION,
                    Constants.TABLE_COLUMNS_METHOD_ANNOTATION);
        }

        List<Object[]> objectList = new ArrayList<>(methodAnnotationList.size());
        for (Pair<String, String> pair : methodAnnotationList) {
            String fullMethod = pair.getLeft();
            String annotation = pair.getRight();
            String methodHash = CommonUtil.genHashWithLen(fullMethod);

            Object[] object = new Object[]{methodHash, annotation, fullMethod};
            objectList.add(object);
        }

        boolean success = dbOperator.batchInsert(sql, objectList);
        methodAnnotationList.clear();
        return success;
    }


    // 读取通过java-callgraph生成的直接调用关系文件，处理方法调用
    private boolean handleMethodCall() {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(confInfo.getCallGraphInputFile()), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (StringUtils.isBlank(line)) {
                    continue;
                }

                if (line.startsWith(Constants.FILE_KEY_METHOD_PREFIX)) {
                    // 处理一条方法调用
                    if (!handleOneMethodCall(line)) {
                        return false;
                    }
                }
            }

            // 结束前将剩余数据写入数据库
            writeMethodCall2Db();

            return true;
        } catch (Exception e) {
            logger.error("error ", e);
            return false;
        }
    }

    // 处理一条方法调用
    private boolean handleOneMethodCall(String data) {
        String[] methodCallArray = data.split(Constants.FLAG_SPACE);
        if (methodCallArray.length != 5) {
            logger.error("方法调用信息非法 [{}] [{}]", data, methodCallArray.length);
            return false;
        }

        String callIdStr = methodCallArray[0].substring(Constants.FILE_KEY_PREFIX_LENGTH);
        String callerFullMethod = methodCallArray[1];
        String calleeFullMethod = methodCallArray[2];
        String strCallerLineNum = methodCallArray[3];
        String callerJarNum = methodCallArray[4];

        if (!CommonUtil.isNumStr(callIdStr)) {
            logger.error("方法调用ID非法 [{}] [{}]", data, callIdStr);
            return false;
        }

        if (!CommonUtil.isNumStr(strCallerLineNum)) {
            logger.error("方法调用信息行号非法 [{}] [{}]", data, strCallerLineNum);
            return false;
        }

        if (!CommonUtil.isNumStr(callerJarNum)) {
            logger.error("Jar包序号非法 [{}] [{}]", data, callerJarNum);
            return false;
        }

        int callerLineNum = Integer.parseInt(strCallerLineNum);
        int indexCalleeLeftBracket = calleeFullMethod.indexOf(Constants.FLAG_LEFT_BRACKET);
        int indexCalleeRightBracket = calleeFullMethod.indexOf(Constants.FLAG_RIGHT_BRACKET);

        String callType = calleeFullMethod.substring(indexCalleeLeftBracket + 1, indexCalleeRightBracket);
        String finalCalleeFullMethod = calleeFullMethod.substring(indexCalleeRightBracket + 1).trim();

        logger.debug("\r\n[{}]\r\n[{}]\r\n[{}]\r\n[{}]\r\n[{}]", callType, callerFullMethod, calleeFullMethod, finalCalleeFullMethod, callerLineNum);

        // 根据类名前缀判断是否需要处理
        if (confInfo.isInputIgnoreOtherPackage() && (!isAllowedClassPrefix(callerFullMethod) || !isAllowedClassPrefix(finalCalleeFullMethod))) {
            return true;
        }

        String callerMethodHash = CommonUtil.genHashWithLen(callerFullMethod);
        String calleeMethodHash = CommonUtil.genHashWithLen(finalCalleeFullMethod);

        if (callerMethodHash.equals(calleeMethodHash)) {
            // 对于递归调用，不写入数据库，防止查询时出现死循环
            logger.info("递归调用不写入数据库 {}", callerFullMethod);
            return true;
        }

        String callerMethodName = CommonUtil.getOnlyMethodName(callerFullMethod);
        String calleeMethodName = CommonUtil.getOnlyMethodName(finalCalleeFullMethod);

        String callerFullClassName = CommonUtil.getFullClassNameFromMethod(callerFullMethod);
        String calleeFullClassName = CommonUtil.getFullClassNameFromMethod(finalCalleeFullMethod);

        String callerFullOrSimpleClassName = getFullOrSimpleClassName(callerFullClassName);
        String calleeFullOrSimpleClassName = getFullOrSimpleClassName(calleeFullClassName);

        MethodCallEntity methodCallEntity = new MethodCallEntity();
        methodCallEntity.setId(Integer.valueOf(Integer.parseInt(callIdStr)));
        methodCallEntity.setCallType(callType);
        methodCallEntity.setEnabled(Constants.ENABLED);
        methodCallEntity.setCallerJarNum(callerJarNum);
        methodCallEntity.setCallerMethodHash(callerMethodHash);
        methodCallEntity.setCallerFullMethod(callerFullMethod);
        methodCallEntity.setCallerMethodName(callerMethodName);
        methodCallEntity.setCallerFullClassName(callerFullClassName);
        methodCallEntity.setCallerFullOrSimpleClassName(callerFullOrSimpleClassName);
        methodCallEntity.setCallerLineNum(callerLineNum);
        methodCallEntity.setCalleeMethodHash(calleeMethodHash);
        methodCallEntity.setFinalCalleeFullMethod(finalCalleeFullMethod);
        methodCallEntity.setCalleeMethodName(calleeMethodName);
        methodCallEntity.setCalleeFullClassName(calleeFullClassName);
        methodCallEntity.setCalleeFullOrSimpleClassName(calleeFullOrSimpleClassName);

        methodCallList.add(methodCallEntity);

        if (methodCallList.size() >= Constants.BATCH_SIZE) {
            writeMethodCall2Db();
        }

        return true;
    }

    private void writeMethodCall2Db() {
        if (methodCallList.isEmpty()) {
            return;
        }

        List<Object[]> tmpMethodCallList = genWriteDBList();
        methodCallList.clear();

        // 等待直到允许任务执行
        wait4TPEExecute();

        threadPoolExecutor.execute(() -> {
            logger.info("写入数据库，方法调用关系表 {}", tmpMethodCallList.size());

            String sql = sqlCacheMap.get(Constants.SQL_KEY_INSERT_METHOD_CALL);
            if (sql == null) {
                sql = genAndCacheInsertSql(Constants.SQL_KEY_INSERT_METHOD_CALL,
                        false,
                        Constants.TABLE_PREFIX_METHOD_CALL,
                        Constants.TABLE_COLUMNS_METHOD_CALL);
            }

            if (!dbOperator.batchInsert(sql, tmpMethodCallList)) {
                someTaskFail = true;
            }
        });
    }

    /**
     * 判断当类名为以下前缀时，才处理
     *
     * @param className 类名，或完整方法（类名+方法名+参数）
     * @return true: 需要处理，false: 忽略
     */
    private boolean isAllowedClassPrefix(String className) {
        for (String allowedClassPrefix : allowedClassPrefixSet) {
            if (className.startsWith(allowedClassPrefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 根据完整类名获取对应的类名
     * 若当前简单类名存在1个以上，则返回完整类名
     * 若当前简单类名只有1个，则返回简单类名
     *
     * @param fullClassName 完整类名信息
     * @return 完整类名或简单类名
     */
    private String getFullOrSimpleClassName(String fullClassName) {
        String simpleClassName = CommonUtil.getSimpleClassNameFromFull(fullClassName);
        if (duplicateClassNameSet.contains(simpleClassName)) {
            return fullClassName;
        }
        return simpleClassName;
    }

    private String genAndCacheInsertSql(String key, boolean replaceInto, String tableName, String[] columns) {
        String sql = sqlCacheMap.get(key);
        if (sql == null) {
            sql = replaceInto ? "replace into " : "insert into ";
            sql = sql + tableName + confInfo.getAppName() + SqlUtil.genColumnString(columns) +
                    " values " + SqlUtil.genQuestionString(columns.length);
            cacheSql(key, sql);
        }
        return sql;
    }

    private List<Object[]> genWriteDBList() {
        List<Object[]> tmpMethodCallList = new ArrayList<>(methodCallList.size());
        for (MethodCallEntity methodCallEntity : methodCallList) {
            Object[] object = new Object[]{
                    methodCallEntity.getId(),
                    methodCallEntity.getCallType(),
                    methodCallEntity.getEnabled(),
                    methodCallEntity.getCallerJarNum(),
                    methodCallEntity.getCallerMethodHash(),
                    methodCallEntity.getCallerFullMethod(),
                    methodCallEntity.getCallerMethodName(),
                    methodCallEntity.getCallerFullClassName(),
                    methodCallEntity.getCallerFullOrSimpleClassName(),
                    methodCallEntity.getCallerLineNum(),
                    methodCallEntity.getCalleeMethodHash(),
                    methodCallEntity.getFinalCalleeFullMethod(),
                    methodCallEntity.getCalleeMethodName(),
                    methodCallEntity.getCalleeFullClassName(),
                    methodCallEntity.getCalleeFullOrSimpleClassName()
            };
            tmpMethodCallList.add(object);
        }
        return tmpMethodCallList;
    }
}

