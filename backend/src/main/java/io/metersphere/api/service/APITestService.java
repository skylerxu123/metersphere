package io.metersphere.api.service;

import com.alibaba.fastjson.JSONObject;
import io.metersphere.api.dto.APITestResult;
import io.metersphere.api.dto.QueryAPITestRequest;
import io.metersphere.api.dto.SaveAPITestRequest;
import io.metersphere.api.jmeter.JMeterService;
import io.metersphere.base.domain.*;
import io.metersphere.base.mapper.ApiTestFileMapper;
import io.metersphere.base.mapper.ApiTestMapper;
import io.metersphere.base.mapper.ext.ExtApiTestMapper;
import io.metersphere.commons.constants.APITestStatus;
import io.metersphere.commons.constants.ScheduleGroup;
import io.metersphere.commons.constants.ScheduleType;
import io.metersphere.commons.exception.MSException;
import io.metersphere.commons.utils.BeanUtils;
import io.metersphere.commons.utils.ServiceUtils;
import io.metersphere.commons.utils.SessionUtils;
import io.metersphere.i18n.Translator;
import io.metersphere.job.sechedule.ApiTestJob;
import io.metersphere.service.FileService;
import io.metersphere.service.ScheduleService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional(rollbackFor = Exception.class)
public class APITestService {

    @Resource
    private ApiTestMapper apiTestMapper;
    @Resource
    private ExtApiTestMapper extApiTestMapper;
    @Resource
    private ApiTestFileMapper apiTestFileMapper;
    @Resource
    private FileService fileService;
    @Resource
    private JMeterService jMeterService;
    @Resource
    private APIReportService apiReportService;
    @Resource
    private ScheduleService scheduleService;

    public List<APITestResult> list(QueryAPITestRequest request) {
        request.setOrders(ServiceUtils.getDefaultOrder(request.getOrders()));
        return extApiTestMapper.list(request);
    }

    public List<APITestResult> recentTest(QueryAPITestRequest request) {
        request.setOrders(ServiceUtils.getDefaultOrder(request.getOrders()));
        return extApiTestMapper.list(request);
    }

    public void create(SaveAPITestRequest request, List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException(Translator.get("file_cannot_be_null"));
        }
        ApiTest test = createTest(request);
        saveFile(test.getId(), files);
    }

    public void update(SaveAPITestRequest request, List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException(Translator.get("file_cannot_be_null"));
        }
        deleteFileByTestId(request.getId());
        ApiTest test = updateTest(request);
        saveFile(test.getId(), files);
    }

    public void copy(SaveAPITestRequest request) {
        request.setName(request.getName() + " Copy");
        try {
            checkNameExist(request);
        } catch (Exception e) {
            request.setName(request.getName() + " " + new Random().nextInt(1000));
        }

        // copy test
        ApiTest copy = get(request.getId());
        copy.setId(UUID.randomUUID().toString());
        copy.setName(request.getName());
        copy.setCreateTime(System.currentTimeMillis());
        copy.setUpdateTime(System.currentTimeMillis());
        copy.setStatus(APITestStatus.Saved.name());
        copy.setUserId(Objects.requireNonNull(SessionUtils.getUser()).getId());
        apiTestMapper.insert(copy);
        // copy test file
        ApiTestFile apiTestFile = getFileByTestId(request.getId());
        if (apiTestFile != null) {
            FileMetadata fileMetadata = fileService.copyFile(apiTestFile.getFileId());
            apiTestFile.setTestId(copy.getId());
            apiTestFile.setFileId(fileMetadata.getId());
            apiTestFileMapper.insert(apiTestFile);
        }
    }

    public APITestResult get(String id) {
        APITestResult apiTest = new APITestResult();
        BeanUtils.copyBean(apiTest, apiTestMapper.selectByPrimaryKey(id));
        Schedule schedule = scheduleService.getScheduleByResource(id, ScheduleGroup.API_TEST.name());
        apiTest.setSchedule(schedule);
        return apiTest;
    }

    public ApiTest getApiTestByProjectId(String testId) {
        return apiTestMapper.selectByPrimaryKey(testId);
    }

    public void delete(String testId) {
        deleteFileByTestId(testId);
        apiReportService.deleteByTestId(testId);
        apiTestMapper.deleteByPrimaryKey(testId);
    }

    public String run(SaveAPITestRequest request) {
        ApiTestFile file = getFileByTestId(request.getId());
        if (file == null) {
            MSException.throwException(Translator.get("file_cannot_be_null"));
        }
        byte[] bytes = fileService.loadFileAsBytes(file.getFileId());
        InputStream is = new ByteArrayInputStream(bytes);

        APITestResult apiTest = get(request.getId());
        if (SessionUtils.getUser() == null) {
            apiTest.setUserId(request.getUserId());
        }
        String reportId = apiReportService.create(apiTest, request.getTriggerMode());
        changeStatus(request.getId(), APITestStatus.Running);

        jMeterService.run(request.getId(), is);
        return reportId;
    }

    public void changeStatus(String id, APITestStatus status) {
        ApiTest apiTest = new ApiTest();
        apiTest.setId(id);
        apiTest.setStatus(status.name());
        apiTestMapper.updateByPrimaryKeySelective(apiTest);
    }

    private void checkNameExist(SaveAPITestRequest request) {
        ApiTestExample example = new ApiTestExample();
        example.createCriteria().andNameEqualTo(request.getName()).andProjectIdEqualTo(request.getProjectId()).andIdNotEqualTo(request.getId());
        if (apiTestMapper.countByExample(example) > 0) {
            MSException.throwException(Translator.get("load_test_already_exists"));
        }
    }

    private ApiTest updateTest(SaveAPITestRequest request) {
        checkNameExist(request);
        final ApiTest test = new ApiTest();
        test.setId(request.getId());
        test.setName(request.getName());
        test.setProjectId(request.getProjectId());
        test.setScenarioDefinition(JSONObject.toJSONString(request.getScenarioDefinition()));
        test.setUpdateTime(System.currentTimeMillis());
        test.setStatus(APITestStatus.Saved.name());
        apiTestMapper.updateByPrimaryKeySelective(test);
        return test;
    }

    private ApiTest createTest(SaveAPITestRequest request) {
        checkNameExist(request);
        final ApiTest test = new ApiTest();
        test.setId(request.getId());
        test.setName(request.getName());
        test.setProjectId(request.getProjectId());
        test.setScenarioDefinition(JSONObject.toJSONString(request.getScenarioDefinition()));
        test.setCreateTime(System.currentTimeMillis());
        test.setUpdateTime(System.currentTimeMillis());
        test.setStatus(APITestStatus.Saved.name());
        test.setUserId(Objects.requireNonNull(SessionUtils.getUser()).getId());
        apiTestMapper.insert(test);
        return test;
    }

    private void saveFile(String testId, List<MultipartFile> files) {
        files.forEach(file -> {
            final FileMetadata fileMetadata = fileService.saveFile(file);
            ApiTestFile apiTestFile = new ApiTestFile();
            apiTestFile.setTestId(testId);
            apiTestFile.setFileId(fileMetadata.getId());
            apiTestFileMapper.insert(apiTestFile);
        });
    }

    private void deleteFileByTestId(String testId) {
        ApiTestFileExample ApiTestFileExample = new ApiTestFileExample();
        ApiTestFileExample.createCriteria().andTestIdEqualTo(testId);
        final List<ApiTestFile> ApiTestFiles = apiTestFileMapper.selectByExample(ApiTestFileExample);
        apiTestFileMapper.deleteByExample(ApiTestFileExample);

        if (!CollectionUtils.isEmpty(ApiTestFiles)) {
            final List<String> fileIds = ApiTestFiles.stream().map(ApiTestFile::getFileId).collect(Collectors.toList());
            fileService.deleteFileByIds(fileIds);
        }
    }

    private ApiTestFile getFileByTestId(String testId) {
        ApiTestFileExample ApiTestFileExample = new ApiTestFileExample();
        ApiTestFileExample.createCriteria().andTestIdEqualTo(testId);
        final List<ApiTestFile> ApiTestFiles = apiTestFileMapper.selectByExample(ApiTestFileExample);
        apiTestFileMapper.selectByExample(ApiTestFileExample);
        if (!CollectionUtils.isEmpty(ApiTestFiles)) {
            return ApiTestFiles.get(0);
        } else {
            return null;
        }
    }

    public void updateSchedule(Schedule request) {
        scheduleService.editSchedule(request);
        addOrUpdateApiTestCronJob(request);
    }

    public void createSchedule(Schedule request) {
        scheduleService.addSchedule(buildApiTestSchedule(request));
        addOrUpdateApiTestCronJob(request);
    }

    private Schedule buildApiTestSchedule(Schedule request) {
        Schedule schedule = scheduleService.buildApiTestSchedule(request);
        schedule.setJob(ApiTestJob.class.getName());
        schedule.setGroup(ScheduleGroup.API_TEST.name());
        schedule.setType(ScheduleType.CRON.name());
        return schedule;
    }

    private void addOrUpdateApiTestCronJob(Schedule request) {
        scheduleService.addOrUpdateCronJob(request, ApiTestJob.getJobKey(request.getResourceId()), ApiTestJob.getTriggerKey(request.getResourceId()), ApiTestJob.class);
    }
}
