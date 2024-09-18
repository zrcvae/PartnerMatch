package com.yupi.yupao.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.xiaoymin.knife4j.core.util.StrUtil;
import com.yupi.yupao.common.ErrorCode;
import com.yupi.yupao.exception.BusinessException;
import com.yupi.yupao.model.domain.User;
import com.yupi.yupao.model.domain.UserTeam;
import com.yupi.yupao.model.dto.TeamQuery;
import com.yupi.yupao.model.enums.TeamStatusEnum;
import com.yupi.yupao.model.request.TeamJoinRequest;
import com.yupi.yupao.model.request.TeamQuitRequest;
import com.yupi.yupao.model.request.TeamUpdateRequest;
import com.yupi.yupao.model.vo.TeamUserVO;
import com.yupi.yupao.model.vo.UserVO;
import com.yupi.yupao.service.TeamService;
import com.yupi.yupao.model.domain.Team;
import com.yupi.yupao.mapper.TeamMapper;
import com.yupi.yupao.service.UserService;
import com.yupi.yupao.service.UserTeamService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.CalendarUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.apache.poi.ss.formula.functions.T;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 队伍服务实现类
 *
 * @author <a href="https://github.com/liyupi">程序员鱼皮</a>
 * @from <a href="https://yupi.icu">编程导航知识星球</a>
 */
@Service
public class TeamServiceImpl extends ServiceImpl<TeamMapper, Team>
        implements TeamService {

    @Resource
    private UserTeamService userTeamService;

    @Resource
    private UserService userService;

    @Resource
    private RedissonClient redissonClient;


    @Override
    @Transactional(rollbackFor = Exception.class)
    public long addTeam(Team team, User loginUser) {
        // 请求参数是否为空
        if (team == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 是否登录，未登录不允许创建
        if (loginUser == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 队伍人数 < 1 或 >= 20
        int maxNum = Optional.ofNullable(team.getMaxNum()).orElse(0);
        if (maxNum < 1 || maxNum >= 20) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "队伍人数不符合要求");
        }
        // 队伍标题 <= 20
        String name = team.getName();
        if (StringUtils.isBlank(name) || name.length() > 20) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "队伍标题不符合要求");
        }
        // 队伍描述 <= 512
        String description = team.getDescription();
        if (StringUtils.isBlank(description) || description.length() > 512) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "队伍描述不符合要求");
        }
        // status是否公开，没有值默认为0（公开）
        Integer status = Optional.ofNullable(team.getStatus()).orElse(0);
        TeamStatusEnum enumByValue = TeamStatusEnum.getEnumByValue(status);
        if (enumByValue == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "队伍状态不符合要求");
        }
        // 如果status是否加密状态，一定要求密码，并且密码 <= 32
        String password = team.getPassword();
        if (TeamStatusEnum.SECRET.equals(enumByValue) && StringUtils.isBlank(password) || password.length() > 32) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "输入密码不符合要求");
        }
        // 超时时间 > 当前时间
        Date nowDate = new Date();
        Date expireTime = team.getExpireTime();
        if (expireTime == null || expireTime.before(nowDate)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "队伍过期时间不符合要求");
        }
        // 校验用户最多创建5个队伍
        Long userId = loginUser.getId();
        QueryWrapper<Team> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("userId", userId);
        long hasTeamCount = this.count(queryWrapper);
        if (hasTeamCount >= 5) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 插入队伍信息到队伍表
        team.setId(null);
        team.setUserId(userId);
        boolean save = this.save(team);
        Long teamId = team.getId();
        if (!save || team == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "创建队伍失败");
        }
        // 插入用户 => 队伍关系到关系表
        UserTeam userTeam = new UserTeam();
        userTeam.setUserId(userId);
        userTeam.setTeamId(teamId);
        userTeam.setJoinTime(new Date());
        boolean result = userTeamService.save(userTeam);
        if (!result) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "创建队伍失败");
        }
        return teamId;
    }

    @Override
    public List<TeamUserVO> lists(TeamQuery teamQuery, boolean isAdmin) {
        QueryWrapper<Team> queryWrapper = new QueryWrapper<>();
        // 从请求参数中取出队伍名称等查询条件，如果存在则作为查询条件
        if (teamQuery != null) {
            Long id = teamQuery.getId();
            if (id != null && id > 0) {
                queryWrapper.eq("id", id);
            }
            // 增加根据队伍id列表进行查询
            if(CollectionUtils.isNotEmpty(teamQuery.getIdList())){
                queryWrapper.in("id", teamQuery.getIdList());
            }
            // 根据关键词查询（描述和名称的组合形式,根据名称和描述进行模糊查询的组合
            String searchText = teamQuery.getSearchText();
            if (StringUtils.isNotBlank(searchText)) {
                queryWrapper.and(qw -> qw.like("name", searchText).or().like("description", searchText));
            }
            String name = teamQuery.getName();
            if (StringUtils.isNotBlank(name)) {
                queryWrapper.like("name", name);
            }
            String description = teamQuery.getDescription();
            if (StringUtils.isNotBlank(description)) {
                queryWrapper.like("description", description);
            }
            Integer maxNum = teamQuery.getMaxNum();
            if (maxNum != null && maxNum > 0) {
                queryWrapper.eq("maxNum", maxNum);
            }
            // 根据创建人查询
            Long userId = teamQuery.getUserId();
            if (userId != null && userId > 0) {
                queryWrapper.eq("userId", userId);
            }
            // 只有管理员才能查看加密和非公开房间信息
            Integer status = teamQuery.getStatus();
            TeamStatusEnum statusEnum = TeamStatusEnum.getEnumByValue(status);
            if (statusEnum == null) {
                statusEnum = TeamStatusEnum.PUBLIC;
            }
            // 此处修改逻辑，登录用户就可以看到加密队伍
//            if (!isAdmin && statusEnum.equals(TeamStatusEnum.SECRET)) {
//                throw new BusinessException(ErrorCode.NO_AUTH);
//            }
            queryWrapper.eq("status", status);
        }
        // 不展示已过期的队伍（根据过期时间筛选）
        queryWrapper.and(qw -> qw.gt("expireTime", new Date()).or().isNull("expireTime"));
        List<Team> teamList = this.list(queryWrapper);
        if (CollectionUtils.isEmpty(teamList)) {
            return new ArrayList<>();
        }

        List<TeamUserVO> teamUserVOList = new ArrayList<>();
        // 关联查询队伍创建人的信息
        for (Team team : teamList) {
            Long userId = team.getUserId();
            if (userId == null) {
                continue;
            }
            User user = userService.getById(userId);
            TeamUserVO teamUserVO = new TeamUserVO();
            if (user != null) {
                BeanUtils.copyProperties(team, teamUserVO);
                // 脱敏用户信息
                UserVO userVO = new UserVO();
                BeanUtils.copyProperties(user, userVO);
                teamUserVO.setCreateUser(userVO);
            }
            teamUserVOList.add(teamUserVO);

        }
        //todo 关联查询已加入队伍的用户信息，可能会耗费性能，用SQL方式实现
        return teamUserVOList;

    }

    @Override
    public boolean updateTeam(TeamUpdateRequest teamUpdateRequest, User loginUser) {
        // 判断请求参数是否为空
        if (teamUpdateRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 查询队伍是否存在
        Long id = teamUpdateRequest.getId();
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        Team oldTeam = this.getById(id);
        if (oldTeam == null) {
            throw new BusinessException(ErrorCode.NULL_ERROR, "没有查到更新数据");
        }
        // 只有管理员或者队伍的创建者可以修改
        Team newTeam = new Team();
        BeanUtils.copyProperties(newTeam, teamUpdateRequest);
        boolean isAdmin = userService.isAdmin(loginUser);
        if ((oldTeam.getUserId() != loginUser.getId()) && !isAdmin) {
            throw new BusinessException(ErrorCode.NO_AUTH);
        }
        // 如果队伍状态为加密，必须要有密码
        TeamStatusEnum statusEnum = TeamStatusEnum.getEnumByValue(teamUpdateRequest.getStatus());
        if (statusEnum.equals(TeamStatusEnum.SECRET)) {
            if (StringUtils.isBlank(newTeam.getPassword())) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "加密状态必须要有密码");
            }
        }
        //todo 如果用户传入的新数据和老数据相同，可以不更新
        // 更新成功
        boolean update = this.updateById(newTeam);
        if (!update) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "更新数据失败");
        }
        return true;
    }

    @Override
    public boolean joinTeam(TeamJoinRequest teamJoinRequest, User loginUser) {
        if (teamJoinRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        Long userId = loginUser.getId();
        // 队伍必须存在，未过期的队伍
        Long teamId = teamJoinRequest.getTeamId();
        if (teamId == null || teamId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        Team team = this.getById(teamId);
        if (team == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "队伍不存在");
        }
        Date expireTime = team.getExpireTime();
        if (expireTime != null && expireTime.before(new Date())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "不能加入过期队伍");
        }
        // 不能加入自己的队伍
        if(team.getUserId() == userId){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "不能加入自己的队伍");
        }

        // 禁止加入私有队伍
        TeamStatusEnum statusEnum = TeamStatusEnum.getEnumByValue(team.getStatus());
        if (TeamStatusEnum.PRIVATE.equals(statusEnum)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "私密队伍不能加入");
        }
        // 如果加入的队伍是加密的，必须密码匹配才可以
        String password = teamJoinRequest.getPassword();
        if (TeamStatusEnum.SECRET.equals(statusEnum)) {
            if (password == null || !team.getPassword().equals(password)) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "密码错误");
            }
        }

        /**
         * 当多次快速点击加入队伍时，会出现用户重复加入的情况
         */
        RLock lock = redissonClient.getLock("yupao:joinTeam:lock");
        try {
            // 只有一个线程能获取到锁
            while (true){
                // 判断是否可以获得锁，如果没有获得锁，就会一直抢锁
                if (lock.tryLock(0, -1, TimeUnit.MILLISECONDS)) {
                    System.out.println("getLock: " + Thread.currentThread().getId());
                    // 用户最多加入5个队伍
                    QueryWrapper<UserTeam> queryWrapper = new QueryWrapper<>();
                    queryWrapper.eq("userId", userId);
                    long joinNum = userTeamService.count(queryWrapper);
                    if (joinNum > 5) {
                        throw new BusinessException(ErrorCode.PARAMS_ERROR, "每个用户最多加入5个队伍");
                    }

                    //不能重复加入已经加入的队伍
                    queryWrapper = new QueryWrapper<>();
                    queryWrapper.eq("userId", userId);
                    queryWrapper.eq("teamId", teamId);
                    long hasUserJoinTeam = userTeamService.count(queryWrapper);
                    if(hasUserJoinTeam > 0){
                        throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户已加入该队伍");
                    }

                    // 只能加入未满的队伍
                    QueryWrapper<UserTeam> teamQueryWrapper = new QueryWrapper<>();
                    teamQueryWrapper.eq("teamId", teamId);
                    long teamHasJoinNum = userTeamService.count(teamQueryWrapper);
                    if(team.getMaxNum() <= teamHasJoinNum){
                        throw new BusinessException(ErrorCode.PARAMS_ERROR, "队伍人数已满");
                    }

                    UserTeam userTeam = new UserTeam();
                    userTeam.setJoinTime(new Date());
                    userTeam.setUserId(userId);
                    userTeam.setTeamId(teamId);

                    // 新增队伍-用户关联信息
                    return userTeamService.save(userTeam);
                }
            }
        } catch (InterruptedException e) {
            log.error("doCacheRecommendUser error", e);
            return false;
        } finally {
            // 只能释放自己的锁
            if (lock.isHeldByCurrentThread()) {
                System.out.println("unLock: " + Thread.currentThread().getId());
                lock.unlock();
            }
        }

    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean quitTeam(TeamQuitRequest teamQuitRequest, User loginUser) {
        if(teamQuitRequest == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 检查队伍是否存在
        Long teamId = teamQuitRequest.getTeamId();
        Team team = getTeamById(teamId);
        // 检查我是否已经加入队伍
        long userId = loginUser.getId();
        if(userId == team.getUserId()){
            System.out.println(111);
        }
        UserTeam userTeam = new UserTeam();
        userTeam.setUserId(userId);
        userTeam.setTeamId(teamId);
        QueryWrapper<UserTeam> queryWrapper = new QueryWrapper(userTeam);
        long count = userTeamService.count(queryWrapper);
        if(count == 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "该用户没有加入队伍");
        }

        queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("teamId", teamId);
        long teamHasJoinNum = userTeamService.count(queryWrapper);
        // 如果队伍只剩下一个人
        if(teamHasJoinNum == 1){
            this.removeById(teamId);
            queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("teamId", teamId);
            return userTeamService.remove(queryWrapper);
        }else {
            // 如果队伍还有其他人，将权限转移给除队长外第一个加入队伍的人(如果不是队长，自己退出队伍)
            if(team.getUserId() == userId){
                QueryWrapper queryWrapper1 = new QueryWrapper();
                queryWrapper1.eq("teamId", teamId);
                queryWrapper1.last("order by id asc limit 2");
                List<UserTeam> userTeams = userTeamService.list(queryWrapper1);
                if(CollectionUtils.isEmpty(userTeams) || userTeams.size() <= 1){
                    throw new BusinessException(ErrorCode.SYSTEM_ERROR);
                }
                Long newLeaderId = userTeams.get(1).getUserId();
                Team updateTeam = new Team();
                updateTeam.setUserId(newLeaderId);
                updateTeam.setId(teamId);
                boolean update = this.updateById(updateTeam);
                if(!update){
                    throw new BusinessException(ErrorCode.SYSTEM_ERROR, "更新队长失败");
                }
            }
            queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("userId", userId);
            queryWrapper.eq("teamId", teamId);
            return userTeamService.remove(queryWrapper);
        }
    }


    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteTeam(Long teamId, User loginUser) {
        // 检验队伍是否存在
        Team team = getTeamById(teamId);
        long getTeamId = team.getId();
        // 检验是不是队伍的队长
        if(team.getUserId() != loginUser.getId()){
            throw new BusinessException(ErrorCode.NO_AUTH, "该用户不是队长");
        }
        // 移除所有加入队伍的关联信息
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq("teamId", teamId);
        boolean remove = userTeamService.remove(queryWrapper);
        if(!remove){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "删除队伍关联信息失败");
        }
        // 删除队伍
        return this.removeById(getTeamId);
    }


    private Team getTeamById(Long teamId) {
        if(teamId == null || teamId <= 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        Team team = this.getById(teamId);
        if(team == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "队伍不存在");
        }
        return team;
    }
}






