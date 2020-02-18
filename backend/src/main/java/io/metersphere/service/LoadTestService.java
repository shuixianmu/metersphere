package io.metersphere.service;

import io.metersphere.base.domain.*;
import io.metersphere.base.mapper.*;
import io.metersphere.base.mapper.ext.ExtLoadTestMapper;
import io.metersphere.commons.constants.LoadTestFileType;
import io.metersphere.commons.exception.MSException;
import io.metersphere.commons.utils.IOUtils;
import io.metersphere.controller.request.testplan.*;
import io.metersphere.dto.LoadTestDTO;
import io.metersphere.runner.Engine;
import io.metersphere.runner.EngineFactory;
import org.apache.commons.lang3.RandomUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(rollbackFor = Exception.class)
public class LoadTestService {
    @Resource
    private LoadTestMapper loadTestMapper;
    @Resource
    private ExtLoadTestMapper extLoadTestMapper;
    @Resource
    private ProjectMapper projectMapper;
    @Resource
    private FileMetadataMapper fileMetadataMapper;
    @Resource
    private FileContentMapper fileContentMapper;
    @Resource
    private LoadTestFileMapper loadTestFileMapper;
    @Resource
    private FileService fileService;

    // 测试，模拟数据
    @PostConstruct
    public void initData() {
        if (!CollectionUtils.isEmpty(loadTestMapper.selectByExample(null))) {
            return;
        }

        final List<Project> projects = projectMapper.selectByExample(null);

        for (int i = 0; i < 100; i++) {
            final LoadTestWithBLOBs loadTest = new LoadTestWithBLOBs();
            loadTest.setId(UUID.randomUUID().toString());
            loadTest.setName("load test " + i);
            loadTest.setProjectId(projects.get(RandomUtils.nextInt(0, projects.size())).getId());
            loadTest.setCreateTime(System.currentTimeMillis());
            loadTest.setUpdateTime(System.currentTimeMillis());
            loadTest.setScenarioDefinition(UUID.randomUUID().toString());
            loadTest.setDescription(UUID.randomUUID().toString());
            loadTestMapper.insert(loadTest);
        }
    }

    public List<LoadTestDTO> list(QueryTestPlanRequest request) {
        return extLoadTestMapper.list(request);
    }

    public void delete(DeleteTestPlanRequest request) {
        loadTestMapper.deleteByPrimaryKey(request.getId());

        fileService.deleteFileByTestId(request.getId());
    }

    public void save(SaveTestPlanRequest request, MultipartFile file) {
        if (file == null) {
            throw new IllegalArgumentException("文件不能为空！");
        }

        final FileMetadata fileMetadata = saveFile(file);

        final LoadTestWithBLOBs loadTest = saveLoadTest(request);

        LoadTestFile loadTestFile = new LoadTestFile();
        loadTestFile.setTestId(loadTest.getId());
        loadTestFile.setFileId(fileMetadata.getId());
        loadTestFileMapper.insert(loadTestFile);
    }

    private LoadTestWithBLOBs saveLoadTest(SaveTestPlanRequest request) {
        final LoadTestWithBLOBs loadTest = new LoadTestWithBLOBs();
        loadTest.setId(UUID.randomUUID().toString());
        loadTest.setName(request.getName());
        loadTest.setProjectId(request.getProjectId());
        loadTest.setCreateTime(System.currentTimeMillis());
        loadTest.setUpdateTime(System.currentTimeMillis());
        loadTest.setScenarioDefinition("todo");
        loadTest.setDescription("todo");
        loadTestMapper.insert(loadTest);
        return loadTest;
    }

    private FileMetadata saveFile(MultipartFile file) {
        final FileMetadata fileMetadata = new FileMetadata();
        fileMetadata.setId(UUID.randomUUID().toString());
        fileMetadata.setName(file.getOriginalFilename());
        fileMetadata.setSize(file.getSize());
        fileMetadata.setCreateTime(System.currentTimeMillis());
        fileMetadata.setUpdateTime(System.currentTimeMillis());
        fileMetadata.setType(LoadTestFileType.JMX.name());
        fileMetadataMapper.insert(fileMetadata);

        FileContent fileContent = new FileContent();
        fileContent.setFileId(fileMetadata.getId());
        try {
            fileContent.setFile(IOUtils.toString(file.getInputStream(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            MSException.throwException(e);
        }
        fileContentMapper.insert(fileContent);

        return fileMetadata;
    }

    public void edit(EditTestPlanRequest request, MultipartFile file) {
        // 新选择了一个文件，删除原来的文件
        if (file != null) {
            fileService.deleteFileByTestId(request.getId());
            final FileMetadata fileMetadata = saveFile(file);
            LoadTestFile loadTestFile = new LoadTestFile();
            loadTestFile.setTestId(request.getId());
            loadTestFile.setFileId(fileMetadata.getId());
            loadTestFileMapper.insert(loadTestFile);
        }

        final LoadTestWithBLOBs loadTest = loadTestMapper.selectByPrimaryKey(request.getId());
        if (loadTest == null) {
            MSException.throwException("无法编辑测试，未找到测试：" + request.getId());
        } else {
            loadTest.setName(request.getName());
            loadTest.setProjectId(request.getProjectId());
            loadTest.setUpdateTime(System.currentTimeMillis());
            loadTest.setScenarioDefinition("todo");
            loadTest.setDescription("todo");
            loadTestMapper.updateByPrimaryKeySelective(loadTest);
        }
    }

    public void run(RunTestPlanRequest request) {
        final LoadTestWithBLOBs loadTest = loadTestMapper.selectByPrimaryKey(request.getId());
        if (loadTest == null) {
            MSException.throwException("无法运行测试，未找到测试：" + request.getId());
        }

        final FileMetadata fileMetadata = fileService.getFileMetadataByTestId(request.getId());
        if (fileMetadata == null) {
            MSException.throwException("无法运行测试，无法获取测试文件元信息，测试ID：" + request.getId());
        }

        final FileContent fileContent = fileService.getFileContent(fileMetadata.getId());
        if (fileContent == null) {
            MSException.throwException("无法运行测试，无法获取测试文件内容，测试ID：" + request.getId());
        }

        System.out.println("开始运行：" + loadTest.getName());
        final Engine engine = EngineFactory.createEngine(fileMetadata.getType());
        if (engine == null) {
            MSException.throwException(String.format("无法运行测试，未识别测试文件类型，测试ID：%s，文件类型：%s",
                    request.getId(),
                    fileMetadata.getType()));
        }

        final boolean init = engine.init(EngineFactory.createContext(loadTest, fileContent));
        if (!init) {
            MSException.throwException(String.format("无法运行测试，初始化运行环境失败，测试ID：%s", request.getId()));
        }

        engine.start();

        /// todo：通过调用stop方法能够停止正在运行的engine，但是如果部署了多个backend实例，页面发送的停止请求如何定位到具体的engine
    }
}
