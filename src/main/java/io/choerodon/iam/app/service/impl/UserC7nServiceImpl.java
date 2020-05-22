package io.choerodon.iam.app.service.impl;

import static io.choerodon.iam.infra.utils.SagaTopic.MemberRole.MEMBER_ROLE_UPDATE;
import static io.choerodon.iam.infra.utils.SagaTopic.User.USER_UPDATE;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.hzero.boot.file.FileClient;
import org.hzero.boot.message.MessageClient;
import org.hzero.boot.message.entity.MessageSender;
import org.hzero.boot.message.entity.Receiver;
import org.hzero.boot.oauth.domain.entity.BaseUser;
import org.hzero.boot.oauth.policy.PasswordPolicyManager;
import org.hzero.iam.api.dto.TenantDTO;
import org.hzero.iam.api.dto.UserPasswordDTO;
import org.hzero.iam.app.service.MemberRoleService;
import org.hzero.iam.app.service.UserService;
import org.hzero.iam.domain.entity.*;
import org.hzero.iam.domain.repository.TenantRepository;
import org.hzero.iam.domain.repository.UserRepository;
import org.hzero.iam.domain.vo.UserVO;
import org.hzero.iam.infra.mapper.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import io.choerodon.asgard.saga.annotation.Saga;
import io.choerodon.asgard.saga.producer.StartSagaBuilder;
import io.choerodon.asgard.saga.producer.TransactionalProducer;
import io.choerodon.core.domain.Page;
import io.choerodon.core.enums.MessageAdditionalType;
import io.choerodon.core.exception.CommonException;
import io.choerodon.core.exception.ext.EmptyParamException;
import io.choerodon.core.exception.ext.UpdateException;
import io.choerodon.core.iam.ResourceLevel;
import io.choerodon.core.oauth.CustomUserDetails;
import io.choerodon.core.oauth.DetailsHelper;
import io.choerodon.iam.api.validator.UserPasswordValidator;
import io.choerodon.iam.api.validator.UserValidator;
import io.choerodon.iam.api.vo.*;
import io.choerodon.iam.api.vo.devops.UserAttrVO;
import io.choerodon.iam.app.service.*;
import io.choerodon.iam.infra.annotation.OperateLog;
import io.choerodon.iam.infra.asserts.OrganizationAssertHelper;
import io.choerodon.iam.infra.asserts.ProjectAssertHelper;
import io.choerodon.iam.infra.asserts.RoleAssertHelper;
import io.choerodon.iam.infra.asserts.UserAssertHelper;
import io.choerodon.iam.infra.constant.MessageCodeConstants;
import io.choerodon.iam.infra.dto.*;
import io.choerodon.iam.infra.dto.payload.UserEventPayload;
import io.choerodon.iam.infra.dto.payload.UserMemberEventPayload;
import io.choerodon.iam.infra.enums.MemberType;
import io.choerodon.iam.infra.enums.RoleLabelEnum;
import io.choerodon.iam.infra.feign.DevopsFeignClient;
import io.choerodon.iam.infra.mapper.*;
import io.choerodon.iam.infra.utils.*;
import io.choerodon.iam.infra.valitador.RoleValidator;
import io.choerodon.mybatis.pagehelper.PageHelper;
import io.choerodon.mybatis.pagehelper.domain.PageRequest;

/**
 * @author scp
 * @date 2020/4/1
 * @description
 */
@Service
public class UserC7nServiceImpl implements UserC7nService {
    private static final String ROOT_BUSINESS_TYPE_CODE = "siteAddRoot";

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    private static final String BUSINESS_TYPE_CODE = "addMember";
    private static final String USER_NOT_LOGIN_EXCEPTION = "error.user.not.login";
    private static final String USER_NOT_FOUND_EXCEPTION = "error.user.not.found";
    private static final String USER_ID_NOT_EQUAL_EXCEPTION = "error.user.id.not.equals";
    private static final String SITE_ADMIN_ROLE_CODE = "role/site/default/administrator";
    private static final String ORG_ADMIN_ROLE_CODE = "role/organization/default/administrator";
    private static final Logger LOGGER = LoggerFactory.getLogger(UserC7nServiceImpl.class);

    @Autowired
    private UserMapper userMapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleMapper roleMapper;
    @Autowired
    private UserC7nMapper userC7nMapper;
    @Autowired
    private UserService userService;
    @Autowired
    private TransactionalProducer producer;
    private final ObjectMapper mapper = new ObjectMapper();
    @Autowired
    private FileClient fileClient;
    @Autowired
    private MemberRoleService memberRoleService;
    @Autowired
    private RoleMemberService roleMemberService;
    @Autowired
    private OrganizationUserService organizationUserService;
    @Autowired
    private TenantC7nMapper tenantC7nMapper;
    @Autowired
    private MessageClient messageClient;
    @Autowired
    private MemberRoleC7nMapper memberRoleC7nMapper;
    @Autowired
    private ProjectMapper projectMapper;
    @Autowired
    private PasswordPolicyMapper passwordPolicyMapper;
    @Autowired
    private PasswordPolicyManager passwordPolicyManager;
    @Autowired
    private UserPasswordValidator userPasswordValidator;
    @Autowired
    private RoleAssertHelper roleAssertHelper;

    @Value("${choerodon.devops.message:false}")
    private boolean devopsMessage;

    @Autowired
    private OrganizationAssertHelper organizationAssertHelper;
    @Autowired
    private ProjectAssertHelper projectAssertHelper;
    @Autowired
    private UserAssertHelper userAssertHelper;
    @Autowired
    private DevopsFeignClient devopsFeignClient;
    @Autowired
    private ProjectUserMapper projectUserMapper;

    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private RoleC7nService roleC7nService;
    @Autowired
    private MemberRoleMapper memberRoleMapper;
    @Autowired
    private TenantMapper tenantMapper;

    @Autowired
    private OrganizationResourceLimitService organizationResourceLimitService;

    @Override
    public User queryInfo(Long userId) {
        User user = userAssertHelper.userNotExisted(userId);
        Tenant tenant = organizationAssertHelper.notExisted(user.getOrganizationId());
        user.setTenantName(tenant.getTenantName());
        user.setTenantNum(tenant.getTenantName());
        return user;
    }

    @Override
    public User updateInfo(User user, Boolean checkLogin) {
        if (checkLogin) {
            checkLoginUser(user.getId());
        }
        User dto;
        if (devopsMessage) {
            UserEventPayload userEventPayload = new UserEventPayload();
            dto = userService.updateUser(user);
            userEventPayload.setEmail(dto.getEmail());
            userEventPayload.setId(dto.getId().toString());
            userEventPayload.setName(dto.getRealName());
            userEventPayload.setUsername(dto.getLoginName());
            BeanUtils.copyProperties(dto, dto);
            try {
                producer.apply(StartSagaBuilder.newBuilder()
                                .withSagaCode(USER_UPDATE)
                                .withPayloadAndSerialize(userEventPayload)
                                .withRefId(dto.getId() + "")
                                .withRefType("user"),
                        builder -> {
                        }
                );
            } catch (Exception e) {
                throw new CommonException("error.UserService.updateInfo.event", e);
            }
        } else {
            dto = userService.updateUser(user);
        }
        Tenant organizationDTO = organizationAssertHelper.notExisted(dto.getOrganizationId());
        dto.setTenantName(organizationDTO.getTenantName());
        dto.setTenantNum(organizationDTO.getTenantNum());
        return dto;
    }

    @Override
    public String uploadPhoto(Long id, MultipartFile file) {
        checkLoginUser(id);
        return fileClient.uploadFile(0L, "iam-service", file.getOriginalFilename(), file);
    }

    @Override
    public String savePhoto(Long id, MultipartFile file, Double rotate, Integer axisX, Integer axisY, Integer width, Integer height) {
        checkLoginUser(id);
        try {
            file = ImageUtils.cutImage(file, rotate, axisX, axisY, width, height);
            return fileClient.uploadFile(0L, "iam-service", file.getOriginalFilename(), file);
        } catch (Exception e) {
            LOGGER.warn("error happened when save photo {}", e.getMessage());
            throw new CommonException("error.user.photo.save");
        }
    }

    @Override
    public void check(User user) {
        boolean checkEmail = !StringUtils.isEmpty(user.getEmail());
        boolean checkPhone = !StringUtils.isEmpty(user.getPhone());

        if (!checkEmail && !checkPhone) {
            throw new CommonException("error.user.validation.fields.empty");
        }
        if (checkEmail) {
            checkEmail(user);
        }
        if (checkPhone) {
            checkPhone(user);
        }
    }


    @Override
    public CustomUserDetails checkLoginUser(Long id) {
        CustomUserDetails customUserDetails = DetailsHelper.getUserDetails();
        if (customUserDetails == null) {
            throw new CommonException(USER_NOT_LOGIN_EXCEPTION);
        }
        if (!id.equals(customUserDetails.getUserId())) {
            throw new CommonException(USER_ID_NOT_EQUAL_EXCEPTION);
        }
        return customUserDetails;
    }


    @Override
    public List<User> listUsersByIds(Long[] ids, Boolean onlyEnabled) {
        if (ObjectUtils.isEmpty(ids)) {
            return new ArrayList<>();
        } else {
            return userC7nMapper.listUsersByIds(ids, onlyEnabled);
        }
    }

    @Override
    public List<UserWithGitlabIdVO> listUsersByIds(Set<Long> ids, Boolean onlyEnabled) {
        if (CollectionUtils.isEmpty(ids)) {
            return new ArrayList<>();
        } else {
            List<User> users = userC7nMapper.listUsersByIds(ids.toArray(new Long[0]), onlyEnabled);
            List<UserAttrVO> userAttrVOS = devopsFeignClient.listByUserIds(ids).getBody();
            if (userAttrVOS == null) {
                userAttrVOS = new ArrayList<>();
            }
            Map<Long, Long> userIdMap = userAttrVOS.stream().collect(Collectors.toMap(UserAttrVO::getIamUserId, UserAttrVO::getGitlabUserId));
            // 填充gitlabUserId
            return users.stream().map(user -> toUserWithGitlabIdDTO(user, userIdMap.get(user.getId()))).collect(Collectors.toList());
        }
    }

    @Override
    public List<User> listUsersByEmails(String[] emails) {
        if (ObjectUtils.isEmpty(emails)) {
            return new ArrayList<>();
        } else {
            return userC7nMapper.listUsersByEmails(emails);
        }
    }

    @Override
    public List<User> listUsersByLoginNames(String[] loginNames, Boolean onlyEnabled) {
        if (ObjectUtils.isEmpty(loginNames)) {
            return new ArrayList<>();
        } else {
            return userC7nMapper.listUsersByLoginNames(loginNames, onlyEnabled);
        }
    }

    @Override
    public Long queryOrgIdByEmail(String email) {
        return userAssertHelper.userNotExisted(UserAssertHelper.WhichColumn.EMAIL, email).getOrganizationId();
    }

    @Override
    public Map<String, Object> queryAllAndNewUsers() {
        Map<String, Object> map = new HashMap<>();
        User dto = new User();
        map.put("allUsers", userMapper.selectCount(dto));
        LocalDate localDate = LocalDate.now();
        String begin = localDate.toString();
        String end = localDate.plusDays(1).toString();
        map.put("newUsers", userC7nMapper.newUsersByDate(begin, end));
        return map;
    }

    @Override
    public UserNumberVO countByDate(Long organizationId, Date startTime, Date endTime) {
        UserNumberVO userNumberVO = new UserNumberVO();
        long previousNumber = userC7nMapper.countPreviousNumberByOrgIdAndDate(organizationId, new java.sql.Date(startTime.getTime()));
        List<User> userDTOS = userC7nMapper.selectByOrgIdAndDate(organizationId,
                new java.sql.Date(startTime.getTime()),
                new java.sql.Date(endTime.getTime()));
        // 按日期分组
        Map<String, List<User>> userMap = userDTOS.stream()
                .collect(Collectors.groupingBy(t -> new java.sql.Date(t.getCreationDate().getTime()).toString()));

        ZoneId zoneId = ZoneId.systemDefault();
        LocalDate startDate = startTime.toInstant().atZone(zoneId).toLocalDate();
        LocalDate endDate = endTime.toInstant().atZone(zoneId).toLocalDate();
        long totalNumber = previousNumber;
        while (startDate.isBefore(endDate) || startDate.isEqual(endDate)) {
            long newUserNumber = 0;
            String date = startDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            List<User> userList = userMap.get(date);
            if (!CollectionUtils.isEmpty(userList)) {
                newUserNumber = userList.size();
                totalNumber += newUserNumber;
            }

            userNumberVO.getDateList().add(date);
            userNumberVO.getTotalUserNumberList().add(totalNumber);
            userNumberVO.getNewUserNumberList().add(newUserNumber);

            startDate = startDate.plusDays(1);
        }

        return userNumberVO;
    }


    @Override
    public Page<User> pagingQueryAdminUsers(PageRequest pageRequest, String loginName, String realName, String params) {
        return PageHelper.doPageAndSort(pageRequest, () -> userC7nMapper.selectAdminUserPage(loginName, realName, params, null));
    }

    /**
     * root用户=拥有租户管理角色+平台管理员角色
     *
     * @param ids
     */
    @Saga(code = SagaTopic.User.ASSIGN_ADMIN, description = "分配Root权限同步事件", inputSchemaClass = AssignAdminVO.class)
    @Override
    @Transactional
    public void addAdminUsers(long[] ids) {
        List<Long> adminUserIds = new ArrayList<>();
        for (long id : ids) {
            User dto = userRepository.selectByPrimaryKey(id);
            if (dto != null && !dto.getAdmin()) {
                dto.setAdmin(true);
                adminUserIds.add(id);
                updateSelective(dto);
            }
        }
        //添加成功后发送站内信和邮件通知被添加者
        if (!adminUserIds.isEmpty()) {
            ((UserC7nServiceImpl) AopContext.currentProxy()).sendNotice(adminUserIds, ROOT_BUSINESS_TYPE_CODE, Collections.emptyMap(), 0L, ResourceLevel.SITE);
        }
        if (!adminUserIds.isEmpty()) {
            AssignAdminVO assignAdminVO = new AssignAdminVO(adminUserIds);
            producer.apply(StartSagaBuilder.newBuilder()
                    .withRefId(adminUserIds.stream().map(String::valueOf).collect(Collectors.joining(",")))
                    .withRefType("user")
                    .withSourceId(0L)
                    .withLevel(ResourceLevel.SITE)
                    .withSagaCode(SagaTopic.User.ASSIGN_ADMIN)
                    .withPayloadAndSerialize(assignAdminVO), builder -> {
            });
        }
    }


    @Override
    public void sendNotice(List<Long> userIds, String code,
                           Map<String, String> params, Long sourceId, ResourceLevel resourceLevel) {
        LOGGER.info("ready : send Notice to {} users", userIds.size());
        if (CollectionUtils.isEmpty(userIds)) {
            return;
        }

        ExceptionUtil.doWithTryCatchAndLog(LOGGER,
                () -> doSendNotice(userIds, code, params, sourceId, resourceLevel),
                ex -> LOGGER.info("Failed to send notices. The code is {}, and the users are: {}", code, userIds));
    }

    private void doSendNotice(List<Long> userIds, String code, Map<String, String> params, Long sourceId, ResourceLevel resourceLevel) {
        MessageSender messageSender = new MessageSender();
        messageSender.setTenantId(0L);
        Map<String, Object> additionalParams = new HashMap<>();
        messageSender.setTenantId(0L);
        if (ResourceLevel.ORGANIZATION == resourceLevel) {
            messageSender.setTenantId(sourceId);
            additionalParams.put(MessageAdditionalType.PARAM_TENANT_ID.getTypeName(), sourceId);
        } else if (ResourceLevel.PROJECT == resourceLevel) {
            additionalParams.put(MessageAdditionalType.PARAM_PROJECT_ID.getTypeName(), sourceId);
        }
        messageSender.setReceiverAddressList(constructUsersByIds(userIds));
        messageSender.setArgs(params);
        messageSender.setMessageCode(code);
        messageSender.setAdditionalInformation(additionalParams);

        messageClient.async().sendMessage(messageSender);
    }

    private List<Receiver> constructUsersByIds(List<Long> userIds) {
        List<User> users = userRepository.selectByIds(org.apache.commons.lang.StringUtils.join(userIds, ","));
        if (!CollectionUtils.isEmpty(users)) {
            return users.stream().map(u -> {
                Receiver receiver = new Receiver();
                receiver.setUserId(u.getId());
                receiver.setEmail(u.getEmail());
                receiver.setPhone(u.getPhone());
                receiver.setTargetUserTenantId(Objects.requireNonNull(u.getOrganizationId(), "receiver tenant id can't be null"));
                return receiver;
            }).collect(Collectors.toList());
        }
        return Collections.emptyList();
    }


    @Saga(code = SagaTopic.User.DELETE_ADMIN, description = "用户Root权限被删除事件同步", inputSchemaClass = DeleteAdminVO.class)
    @Override
    public void deleteAdminUser(long id) {
        User userDTO = new User();
        userDTO.setAdmin(true);
        if (userMapper.selectCount(userDTO) > 1) {
            User dto = userAssertHelper.userNotExisted(id);
            if (dto.getAdmin()) {
                dto.setAdmin(false);
                producer.apply(StartSagaBuilder.newBuilder()
                                .withRefId(String.valueOf(id))
                                .withRefType("user")
                                .withSourceId(0L)
                                .withLevel(ResourceLevel.SITE)
                                .withSagaCode(SagaTopic.User.DELETE_ADMIN)
                                .withPayloadAndSerialize(new DeleteAdminVO(id)),
                        builder -> updateSelective(dto));
            }
        } else {
            throw new CommonException("error.user.admin.size");
        }
    }


    @Override
    public Page<TenantVO> pagingQueryOrganizationsWithRoles(PageRequest pageRequest, Long id, String params) {
        int page = pageRequest.getPage();
        int size = pageRequest.getSize();
        Page<TenantVO> result = new Page<>();
        int start = PageUtils.getBegin(page, size);
        int count = memberRoleC7nMapper.selectCountBySourceId(id, "organization");
        result.setSize(count);
        result.getContent().addAll(tenantC7nMapper.selectOrganizationsWithRoles(id, start, size, params));
        return result;
    }


    @Override
    public Page<ProjectDTO> pagingQueryProjectAndRolesById(PageRequest pageRequest, Long id, String params) {
        int page = pageRequest.getPage();
        int size = pageRequest.getSize();
        Page<ProjectDTO> result = new Page<>();

        if (size == 0) {
            List<ProjectDTO> projectList = projectMapper.selectProjectsWithRoles(id, null, null, params);
            result.setSize(projectList.size());
            result.getContent().addAll(projectList);
        } else {
            int start = PageUtils.getBegin(page, size);
            ProjectUserDTO projectUserDTO = new ProjectUserDTO();
            projectUserDTO.setMemberId(id);
            int count = projectUserMapper.selectCount(projectUserDTO);
            result.setSize(count);
            List<ProjectDTO> projectList = projectMapper.selectProjectsWithRoles(id, start, size, params);
            result.getContent().addAll(projectList);
        }
        return result;
    }


    @Override
    public Boolean checkIsRoot(Long id) {
        List<User> userList = userC7nMapper.selectAdminUserPage(null, null, null, id);
        if (!CollectionUtils.isEmpty(userList)) {
            return userList.contains(id);
        } else {
            return false;
        }
    }

    @Override
    public List<ProjectDTO> queryProjects(Long userId, Boolean includedDisabled) {
        CustomUserDetails customUserDetails = checkLoginUser(userId);
        boolean isAdmin = false;
        if (customUserDetails.getAdmin() != null) {
            isAdmin = customUserDetails.getAdmin();
        }
        ProjectDTO project = new ProjectDTO();
        if (!isAdmin && includedDisabled != null && !includedDisabled) {
            project.setEnabled(true);
        }
        List<ProjectDTO> projects = projectMapper.selectAllProjectsByUserIdOrAdmin(userId, project, isAdmin);
        projects.forEach(p -> p.setCategory(p.getCategories().get(0).getCode()));
        return projects;
    }


    @Override
    public Page<User> pagingQueryUsersWithRolesOnSiteLevel(PageRequest pageRequest, String orgName, String loginName, String realName,
                                                           String roleName, Boolean enabled, Boolean locked, String params) {
        int page = pageRequest.getPage();
        int size = pageRequest.getSize();
        boolean doPage = (size != 0);
        Page<User> result = new Page<>();
        if (doPage) {
            int start = PageUtils.getBegin(page, size);
            int count = userC7nMapper.selectCountUsersOnSiteLevel(ResourceLevel.SITE.value(), 0L, orgName, loginName, realName,
                    roleName, enabled, locked, params);
            List<User> users = userC7nMapper.selectUserWithRolesOnSiteLevel(start, size, ResourceLevel.SITE.value(), 0L, orgName,
                    loginName, realName, roleName, enabled, locked, params);
            result.setTotalElements(count);
            result.getContent().addAll(users);
        } else {
            List<User> users = userC7nMapper.selectUserWithRolesOnSiteLevel(null, null, ResourceLevel.SITE.value(), 0L, orgName,
                    loginName, realName, roleName, enabled, locked, params);
            result.setTotalElements(users.size());
            result.getContent().addAll(users);
        }
        return result;
    }

    @Override
    public OrganizationProjectVO queryOrganizationProjectByUserId(Long userId) {
        OrganizationProjectVO organizationProjectDTO = new OrganizationProjectVO();
        organizationProjectDTO.setOrganizationList(tenantC7nMapper.selectFromMemberRoleByMemberId(userId, false).stream().map(organizationDO ->
                OrganizationProjectVO.newInstanceOrganization(organizationDO.getTenantId(), organizationDO.getTenantName(), organizationDO.getTenantNum())).collect(Collectors.toList()));
        ProjectDTO projectDTO = new ProjectDTO();
        projectDTO.setEnabled(true);
        organizationProjectDTO.setProjectList(projectMapper.selectProjectsByUserId(userId, projectDTO)
                .stream().map(p -> OrganizationProjectVO.newInstanceProject(p.getId(), p.getName(), p.getCode())).collect(Collectors.toList()));
        return organizationProjectDTO;
    }

    @Override
    public List<UserWithGitlabIdDTO> listUsersWithRolesAndGitlabUserIdByIdsInOrg(Long organizationId, Set<Long> userIds) {
        // TODO
        return null;
    }

    @Override
    public List<ProjectDTO> listProjectsByUserId(Long organizationId, Long userId, ProjectDTO projectDTO, String params) {
        boolean isAdmin = checkIsRoot(userId);
        boolean isOrgAdmin = checkIsOrgRoot(organizationId, userId);
        List<ProjectDTO> projects = new ArrayList<>();
        // 普通用户只能查到启用的项目
        if (!isAdmin && !isOrgAdmin) {
            if (projectDTO.getEnabled() != null && !projectDTO.getEnabled()) {
                return projects;
            } else {
                projectDTO.setEnabled(true);
            }
        }
        projects = projectMapper.selectProjectsByUserIdOrAdmin(organizationId, userId, projectDTO, isAdmin, isOrgAdmin, params);
        setProjectsInto(projects, isAdmin, isOrgAdmin);
        return projects;
    }

    @Override
    public Boolean checkIsGitlabOwner(Long id, Long projectId, String level) {
        List<RoleC7nDTO> roleC7nDTOList = userC7nMapper.selectRolesByUidAndProjectId(id, projectId);
        if (!CollectionUtils.isEmpty(roleC7nDTOList)) {
            List<Label> labels = new ArrayList<>();
            roleC7nDTOList.forEach(t -> labels.addAll(t.getLabels()));
            List<String> labelNameLists = labels.stream().map(Label::getName).collect(Collectors.toList());
            return labelNameLists.contains(RoleLabelEnum.GITLAB_OWNER.value());
        }
        return false;
    }

    @Override
    public Boolean checkIsProjectOwner(Long id, Long projectId) {
        List<RoleC7nDTO> roleDTOList = userC7nMapper.selectRolesByUidAndProjectId(id, projectId);
        return !CollectionUtils.isEmpty(roleDTOList) && roleDTOList.stream().anyMatch(v -> RoleLabelEnum.PROJECT_ADMIN.value().equals(v.getCode()));
    }

    @Override
    public Page<OrgAdministratorVO> pagingQueryOrgAdministrator(PageRequest pageable, Long organizationId,
                                                                String realName, String loginName, String params) {
        Page<UserDTO> userDTOPageInfo = PageHelper.doPageAndSort(pageable, () -> userC7nMapper.listOrgAdministrator(organizationId, realName, loginName, params));
        List<UserDTO> userDTOList = userDTOPageInfo.getContent();
        List<OrgAdministratorVO> orgAdministratorVOS = new ArrayList<>();
        Page<OrgAdministratorVO> pageInfo = new Page<>();
        BeanUtils.copyProperties(userDTOPageInfo, pageInfo);
        if (!CollectionUtils.isEmpty(userDTOList)) {
            userDTOList.forEach(user -> {
                OrgAdministratorVO orgAdministratorVO = new OrgAdministratorVO();
                orgAdministratorVO.setEnabled(user.getEnabled());
                orgAdministratorVO.setLocked(user.getLocked());
                orgAdministratorVO.setUserName(user.getRealName());
                orgAdministratorVO.setId(user.getId());
                orgAdministratorVO.setLoginName(user.getLoginName());
                orgAdministratorVO.setCreationDate(user.getCreationDate());
                orgAdministratorVO.setExternalUser(!organizationId.equals(user.getOrganizationId()));
                orgAdministratorVOS.add(orgAdministratorVO);
            });
            pageInfo.setContent(orgAdministratorVOS);
        }
        return pageInfo;
    }

    private Long getRoleByCode(String code) {
        Role query = new Role();
        query.setCode(code);
        Role role = roleMapper.selectOne(query);
        if (role == null) {
            throw new CommonException("error.get.code.role");
        }
        return role.getId();
    }

    /**
     * 校验在启用用户中手机号唯一
     *
     * @param user 用户信息
     */
    private void checkPhone(User user) {
        boolean createCheck = StringUtils.isEmpty(user.getId());
        String phone = user.getPhone();
        User userDTO = new User();
        userDTO.setPhone(phone);
        userDTO.setEnabled(true);
        if (createCheck) {
            List<User> select = userMapper.select(userDTO);
            boolean existed = select != null && select.size() != 0;
            if (existed) {
                throw new CommonException("error.user.phone.exist");
            }
        } else {
            Long id = user.getId();
            User dto = userMapper.selectOne(userDTO);
            boolean existed = dto != null && !id.equals(dto.getId());
            if (existed) {
                throw new CommonException("error.user.phone.exist");
            }
        }
    }

    private void checkEmail(User user) {
        boolean createCheck = StringUtils.isEmpty(user.getId());
        String email = user.getEmail();
        User userDTO = new User();
        userDTO.setEmail(email);
        if (createCheck) {
            boolean existed = userMapper.selectOne(userDTO) != null;
            if (existed) {
                throw new CommonException("error.user.email.existed");
            }
        } else {
            Long id = user.getId();
            User dto = userMapper.selectOne(userDTO);
            boolean existed = dto != null && !id.equals(dto.getId());
            if (existed) {
                throw new CommonException("error.user.email.existed");
            }
        }
    }

    private UserWithGitlabIdVO toUserWithGitlabIdDTO(User user, @Nullable Long gitlabUserId) {
        if (user == null) {
            return null;
        }
        UserWithGitlabIdVO userWithGitlabIdVO = new UserWithGitlabIdVO();
        BeanUtils.copyProperties(user, userWithGitlabIdVO);
        userWithGitlabIdVO.setGitlabUserId(gitlabUserId);
        return userWithGitlabIdVO;
    }

    @Override
    public Boolean checkIsOrgRoot(Long organizationId, Long userId) {
        return userC7nMapper.isOrgAdministrator(organizationId, userId);
    }


    @Override
    public User updateUserRoles(Long userId, String sourceType, Long sourceId, List<Role> roleList) {
        return updateUserRoles(userId, sourceType, sourceId, roleList, false);
    }

    @Override
    public User updateUserRoles(Long userId, String sourceType, Long sourceId, List<Role> roleList, Boolean syncAll) {
        UserValidator.validateUseRoles(roleList, true);
        User user = userAssertHelper.userNotExisted(userId);
        validateSourceNotExisted(sourceType, sourceId);
        createUserRoles(user, roleList, sourceType, sourceId, true, true, true, syncAll);
        return user;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @OperateLog(type = "assignUsersRoles", content = "用户%s被%s分配【%s】角色", level = {ResourceLevel.SITE, ResourceLevel.ORGANIZATION})
    public List<MemberRole> assignUsersRoles(String sourceType, Long sourceId, List<MemberRole> memberRoleDTOList) {
        validateSourceNotExisted(sourceType, sourceId);
        // 校验组织人数是否已达上限
        if (ResourceLevel.ORGANIZATION.value().equals(sourceType)) {
            Set<Long> userIds = memberRoleDTOList.stream().map(MemberRole::getMemberId).collect(Collectors.toSet());
            organizationResourceLimitService.checkEnableCreateUserOrThrowE(sourceId, userIds.size());
        }
        if (ResourceLevel.PROJECT.value().equals(sourceType)) {
            Set<Long> userIds = memberRoleDTOList.stream().map(MemberRole::getMemberId).collect(Collectors.toSet());
            ProjectDTO projectDTO = projectMapper.selectByPrimaryKey(sourceId);
            organizationResourceLimitService.checkEnableCreateUserOrThrowE(projectDTO.getOrganizationId(), userIds.size());
        }
        memberRoleDTOList.forEach(memberRoleDTO -> {
            if (memberRoleDTO.getRoleId() == null || memberRoleDTO.getMemberId() == null) {
                throw new EmptyParamException("error.memberRole.insert.empty");
            }
            memberRoleDTO.setMemberType(MemberType.USER.value());
            memberRoleDTO.setSourceType(sourceType);
            memberRoleDTO.setSourceId(sourceId);
        });
        Map<Long, List<MemberRole>> memberRolesMap = memberRoleDTOList.stream().collect(Collectors.groupingBy(MemberRole::getMemberId));
        List<MemberRole> result = new ArrayList<>();
        memberRolesMap.forEach((memberId, memberRoleDTOS) -> result.addAll(roleMemberService.insertOrUpdateRolesOfUserByMemberId(false, sourceId, memberId, memberRoleDTOS, sourceType)));
        return result;
    }

    @Override
    public List<MemberRole> createUserRoles(User userDTO, List<Role> roleList, String sourceType, Long sourceId, boolean isEdit, boolean allowRoleEmpty, boolean allowRoleDisable) {
        return createUserRoles(userDTO, roleList, sourceType, sourceId, isEdit, allowRoleEmpty, allowRoleDisable, false);
    }

    @Override
    public List<MemberRole> createUserRoles(User user, List<Role> roleDTOList, String sourceType, Long sourceId, boolean isEdit, boolean allowRoleEmpty, boolean allowRoleDisable, Boolean syncAll) {
        Long userId = user.getId();
        List<MemberRole> memberRoleS = new ArrayList<>();
        if (!CollectionUtils.isEmpty(roleDTOList)) {
            List<Role> resultRoles = new ArrayList<>();
            for (Role role : roleDTOList) {
                Role existRole = roleAssertHelper.roleNotExisted(role.getId());
                RoleValidator.validateRole(sourceType, sourceId, role, allowRoleDisable);
                resultRoles.add(existRole);
                MemberRole memberRole = new MemberRole();
                memberRole.setMemberId(userId);
                memberRole.setMemberType(ResourceLevel.USER.value());
                memberRole.setSourceId(sourceId);
                memberRole.setSourceType(sourceType);
                memberRole.setRoleId(role.getId());
                memberRoleS.add(memberRole);
            }
            user.setRoles(resultRoles);
            memberRoleS = roleMemberService.insertOrUpdateRolesOfUserByMemberId(isEdit, sourceId, userId, memberRoleS, sourceType, syncAll);
        } else {
            // 如果允许用户角色为空 则清空当前用户角色
            if (allowRoleEmpty) {
                memberRoleS = roleMemberService.insertOrUpdateRolesOfUserByMemberId(isEdit, sourceId, userId, new ArrayList<>(), sourceType, syncAll);
            }
        }
        return memberRoleS;
    }


    private void validateSourceNotExisted(String sourceType, Long sourceId) {
        if (ResourceLevel.ORGANIZATION.value().equals(sourceType)) {
            organizationAssertHelper.notExisted(sourceId);
        }
        if (ResourceLevel.PROJECT.value().equals(sourceType)) {
            projectAssertHelper.projectNotExisted(sourceId);
        }
    }

    @Override
    public List<User> listProjectUsersByProjectIdAndRoleLable(Long projectId, String roleLable) {
        return null;
    }

    @Override
    public List<User> listUsersByName(Long projectId, String param) {
        return null;
    }

    @Override
    public List<User> listProjectOwnerById(Long projectId) {
        return null;
    }

    @Override
    public List<UserWithGitlabIdDTO> listUsersWithRolesAndGitlabUserIdByIdsInProject(Long projectId, Set<Long> userIds) {
        return null;
    }

    @Override
    public List<User> listUsersWithRolesOnProjectLevel(Long projectId, String loginName, String realName, String roleName, String params) {
        return null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserInfoDTO updateUserInfo(Long id, UserInfoDTO userInfoDTO) {
        // 更新用户密码
        UserPasswordDTO passwordDTO = new UserPasswordDTO();
        passwordDTO.setOriginalPassword(userInfoDTO.getOriginalPassword());
        passwordDTO.setPassword(userInfoDTO.getPassword());
        selfUpdatePassword(id, passwordDTO, true, false);
        // 更新用户名
        String userName = userInfoDTO.getUserName();
        if (!StringUtils.isEmpty(userName)) {
            User user = userMapper.selectByPrimaryKey(id);
            user.setRealName(userName);
            updateInfo(user, false);
        }
        return userInfoDTO;
    }

    @Override
    public void selfUpdatePassword(Long userId, UserPasswordDTO userPasswordDTO, Boolean checkPassword, Boolean checkLogin) {
        if (checkLogin) {
            checkLoginUser(userId);
        }
        User user = userAssertHelper.userNotExisted(userId);
        if (user.getLdap()) {
            throw new CommonException("error.ldap.user.can.not.update.password");
        }
        if (!ENCODER.matches(userPasswordDTO.getOriginalPassword(), user.getPassword())) {
            throw new CommonException("error.password.originalPassword");
        }
        //密码策略
        if (checkPassword) {
            BaseUser baseUserDTO = new BaseUser();
            BeanUtils.copyProperties(user, baseUserDTO);
            Tenant organizationDTO = tenantRepository.selectByPrimaryKey(user.getOrganizationId());
            if (organizationDTO != null) {
                PasswordPolicy example = new PasswordPolicy();
                example.setOrganizationId(organizationDTO.getTenantId());
                if (userPasswordDTO.getPassword() != null) {
                    passwordPolicyManager.passwordValidate(userPasswordDTO.getPassword(), organizationDTO.getTenantId(), baseUserDTO);
                }
                // 校验用户密码
                userPasswordValidator.validate(userPasswordDTO.getPassword(), organizationDTO.getTenantId(), true);
            }
        }
        user.setPassword(ENCODER.encode(userPasswordDTO.getPassword()));
        updateSelective(user);
        // TODO 用户更新密码逻辑需要考虑如何修改
//        passwordRecord.updatePassword(user.getId(), user.getPassword());

        // send siteMsg
        Map<String, String> paramsMap = new HashMap<>();
        paramsMap.put("userName", user.getRealName());
        List<Long> userIds = new ArrayList<>();
        userIds.add(user.getId());
        sendNotice(userIds, "modifyPassword", paramsMap, 0L, ResourceLevel.SITE);
    }


    @Override
    public User updateUserDisabled(Long userId) {
        User user = userAssertHelper.userNotExisted(userId);
        user.setEnabled(false);
        return updateSelective(user);
    }

    @Override
    public UserDTO queryByLoginName(String loginName) {
        return userC7nMapper.queryUserByLoginName(Objects.requireNonNull(loginName));
    }

    @Override
    public List<UserDTO> listUsersWithGitlabLabel(Long projectId, String labelName, RoleAssignmentSearchDTO roleAssignmentSearchDTO) {
        String param = Optional.ofNullable(roleAssignmentSearchDTO).map(dto -> ParamUtils.arrToStr(dto.getParam())).orElse(null);
        return ConvertUtils.convertList(userC7nMapper.listUsersWithGitlabLabel(projectId, labelName, roleAssignmentSearchDTO, param), UserDTO.class);
    }

    private void setProjectsInto(List<ProjectDTO> projects, boolean isAdmin, boolean isOrgAdmin) {
        if (!CollectionUtils.isEmpty(projects)) {
            projects.forEach(p -> {
                p.setCategory(p.getCategories().get(0).getCode());
                // 如果项目为禁用 不可进入
                if (p.getEnabled() == null || !p.getEnabled()) {
                    p.setInto(false);
                    return;
                }
                // 如果不是admin用户和组织管理员且未分配项目角色 不可进入
                if (!isAdmin && !isOrgAdmin && CollectionUtils.isEmpty(p.getRoles())) {
                    p.setInto(false);
                }

            });
        }
    }

    private User updateSelective(User user) {
        userAssertHelper.objectVersionNumberNotNull(user.getObjectVersionNumber());
        if (userMapper.updateByPrimaryKeySelective(user) != 1) {
            throw new UpdateException("error.user.update");
        }
        return userMapper.selectByPrimaryKey(user);
    }

    @Override
    public UserVO selectSelf() {
        UserVO userVO = userRepository.selectSelf();
        User user = userRepository.selectByPrimaryKey(userVO.getId());
        userVO.setObjectVersionNumber(user.getObjectVersionNumber());
        userVO.setAdmin(user.getAdmin());
        if (!user.getAdmin()) {
            List<TenantDTO> list = tenantRepository.selectSelfTenants(null);
            if (CollectionUtils.isEmpty(list)) {
                throw new CommonException("error.get.user.tenants");
            }
            userVO.setRecentAccessTenantList(ConvertUtils.convertList(list, Tenant.class));
        }
        return userVO;
    }

    @Override
    public List<User> listEnableUsersByName(String sourceType, Long sourceId, String userName) {
        validateSourceNotExisted(sourceType, sourceId);
        return userC7nMapper.listEnableUsersByName(sourceType, sourceId, userName);
    }

    @Override
    @Transactional
    @Saga(code = MEMBER_ROLE_UPDATE, description = "iam更新用户角色", inputSchemaClass = List.class)
    public void createOrgAdministrator(List<Long> userIds, Long organizationId) {
        Role tenantAdminRole = roleC7nService.getTenantAdminRole(organizationId);
        Tenant tenant = tenantMapper.selectByPrimaryKey(organizationId);
        Set<Long> notifyUserIds = new HashSet<>();
        Map<Long, List<MemberRole>> listMap = new HashMap<>();
        List<UserMemberEventPayload> userMemberEventPayloads = new ArrayList<>();
        Set<String> labelNames = new HashSet<>();
        // 接收者
        List<Receiver> receiverList = new ArrayList<>();

        userIds.forEach(id -> {
            List<MemberRole> memberRoleList = new ArrayList<>();
            MemberRole memberRoleDTO = new MemberRole();
            memberRoleDTO.setRoleId(tenantAdminRole.getId());
            memberRoleDTO.setMemberId(id);
            memberRoleDTO.setMemberType(MemberType.USER.value());
            memberRoleDTO.setSourceId(organizationId);
            memberRoleDTO.setSourceType(ResourceLevel.ORGANIZATION.value());
            memberRoleList.add(memberRoleDTO);

            memberRoleService.batchAssignMemberRole(memberRoleList);

            // 构建saga对象
            labelNames.add(RoleLabelEnum.TENANT_ADMIN.value());
            UserMemberEventPayload userMemberEventPayload = new UserMemberEventPayload();
            userMemberEventPayload.setUserId(id);
            userMemberEventPayload.setResourceId(organizationId);
            userMemberEventPayload.setResourceType(ResourceLevel.ORGANIZATION.value());
            userMemberEventPayload.setRoleLabels(labelNames);
            userMemberEventPayloads.add(userMemberEventPayload);


            // 构建消息对象
            User user = userMapper.selectByPrimaryKey(id);
            notifyUserIds.add(id);

            Receiver receiver = new Receiver();
            receiver.setUserId(id);
            // 发送邮件消息时 必填
            receiver.setEmail(user.getEmail());
            receiverList.add(receiver);
        });

        // 发送saga同步角色
        producer.apply(StartSagaBuilder.newBuilder()
                        .withRefId(String.valueOf(DetailsHelper.getUserDetails().getUserId()))
                        .withRefType("user")
                        .withSourceId(organizationId)
                        .withLevel(ResourceLevel.ORGANIZATION)
                        .withSagaCode(MEMBER_ROLE_UPDATE)
                        .withPayloadAndSerialize(userMemberEventPayloads),
                builder -> {
                });


        // 准备消息发送的messageSender
        MessageSender messageSender = new MessageSender();
        // 消息code
        messageSender.setMessageCode(MessageCodeConstants.BUSINESS_TYPE_CODE);
        // 默认为0L,都填0L,可不填写
        messageSender.setTenantId(0L);

        // 消息参数 消息模板中${projectName}
        Map<String, String> argsMap = new HashMap<>();
        argsMap.put("organizationName", tenant.getTenantName());
        argsMap.put("roleName", "租户管理员");
        argsMap.put("organizationId", tenant.getTableId());
        argsMap.put("addCount", String.valueOf(userIds.size()));
        messageSender.setArgs(argsMap);

        messageSender.setReceiverAddressList(receiverList);

        Map<String, Object> objectMap = new HashMap<>();
        objectMap.put(MessageAdditionalType.PARAM_TENANT_ID.getTypeName(), organizationId);
        messageSender.setAdditionalInformation(objectMap);


        //添加组织管理员发送消息通知被添加者,异步发送消息
        messageClient.async().sendMessage(messageSender);


        //添加webhook json
//        JSONObject jsonObject = new JSONObject();
//        jsonObject.put("organizationId", organizationDTO.getId());
//        jsonObject.put("addCount", notifyUserIds.size());
//        List<WebHookJsonSendDTO.User> userList = new ArrayList<>();
//        if (!CollectionUtils.isEmpty(userList)) {
//            for (Long notifyUserId : notifyUserIds) {
//                userList.add(userService.getWebHookUser(notifyUserId));
//            }
//        }
//
//        jsonObject.put("userList", JSON.toJSONString(userList));
//        WebHookJsonSendDTO webHookJsonSendDTO = new WebHookJsonSendDTO(
//                SendSettingBaseEnum.ADD_MEMBER.value(),
//                SendSettingBaseEnum.map.get(SendSettingBaseEnum.ADD_MEMBER.value()),
//                jsonObject,
//                new Date(),
//                userService.getWebHookUser(customUserDetails.getUserId())
//        );
//        userService.sendNotice(customUserDetails.getUserId(), new ArrayList<>(notifyUserIds), BUSINESS_TYPE_CODE, params, organizationId, webHookJsonSendDTO);

    }

    @Override
    public Page<SimplifiedUserVO> pagingQueryAllUser(PageRequest pageRequest, String param, Long organizationId) {
        if (StringUtils.isEmpty(param) && Long.valueOf(0).equals(organizationId)) {
           return new Page<>();
        }
        if (organizationId.equals(0L)) {
            return PageHelper.doPage(pageRequest,() -> userC7nMapper.selectAllUsersSimplifiedInfo(param));
        } else {
            return PageHelper.doPage(pageRequest,() -> userC7nMapper.selectUsersOptional(param, organizationId));
        }
    }
}
