package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 王雄俊
 * @since 2024-01-02
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    //通过Resource注解注入SpringData提供的API
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //校验手机号，校验过程是需要正则表达式的，但这个过程已经被封装到utils包下面了，直接调用方法就行
        if (RegexUtils.isPhoneInvalid(phone)) {
            //不符合，返回错误信息
            return Result.fail("手机号格式错误");
        }
        //符合，生成6位校验码
        String code = RandomUtil.randomNumbers(6);
        /**
         * session.setAttribute("code", code);
         * 保存验证码到session，这个过程被替代成保存到Redis！
         */
        /**
         * 保存验证码到Redis，其中key是phone（加一下业务前缀防止冲突），value是String类型
         * 我们要对key设置一下有效期为2分钟，防止网站被无限的注册攻击而导致内存爆炸
         * 代码中的一些内容用常量来替代，常量另外保存到其他的类中，看起来更规范
         */
        //stringRedisTemplate.opsForValue().set("login:code" + phone, code, 2, TimeUnit.MINUTES);
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        //发送验证码，这一般要用第三方服务，这边就做个简单的日志记录一下就好
        log.debug("成功发送短信验证码：{}", code);
        System.out.println(code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            //不符合，返回错误信息
            return Result.fail("手机号格式错误");
        }
        // TODO 从Redis中获取验证码来做校验
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if (cacheCode == null || !cacheCode.equals(code)){
            //不一致，报错
            return Result.fail("验证码错误");
        }
        User user = query().eq("phone", phone).one();
        //判断用户是否存在
        if (user == null){
            //不存在，创建新用户并保存
            user = createUserWithPhone(phone);
        }
        /**
         * session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
         * 之前保存用户信息到session中，现在改成保存到Redis中去
         */
        //随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString(true);
        /**
         *  将User对象转换为Hash存储
         *  1、转换成UserDTO
         *  2、将其转换成Map的形式
         *  3、用Hash结构的putAll方法，因为UserDTO还是包含了多个字段和字段值
         *  4、要给token设置一个有效期，30min没操作就退出登录（效仿session），putAll没有对应的参数选择，要单独用expire()设
         *  5、要注意一个细节，每次用户操作了就要重新去更新这30min（这里我们可以用拦截器来看用户什么时候操作了系统）
         */
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));//转换为字符串
        //存储
        String tokenKey = LOGIN_USER_KEY + token;//LOGIN_USER_KEY="login:user:"
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        //设置有效期
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);//LOGIN_USER_TTL=30L

        //返回token
        return Result.ok(token);
    }

    @Override
    public Result queryUserById(Long userId) {
        //查询详情
        User user = getById(userId);
        if(user == null){
            return Result.ok();
        }
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        return Result.ok(userDTO);
    }

    @Override
    public Result sign() {
        //获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //获取当前日期
        LocalDateTime now = LocalDateTime.now();
        //将用户与日期拼接成key，日期只需要年月的字符串格式
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;//"sign:"
        //获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //写入Redis SETBIT key offset 1
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        //获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //获取当前日期
        LocalDateTime now = LocalDateTime.now();
        //将用户与日期拼接成key，日期只需要年月的字符串格式
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;//"sign:"
        //获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //获取本月截止今天位置的所有签到记录，返回十进制数字
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                //设置为无符号数，且从第0位开始算，抽取出到今天为止的bit位
                BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if(result == null || result.isEmpty()){
            return Result.ok(0);
        }
        //与1做与运算，得到数字的最后一个bit位
        Long num = result.get(0);
        if(num == 0 || num == null){
            //为0，未签到，结束
            return Result.ok(0);
        }
        //为1，循环遍历，为1则计数器+1
        int count = 0;
        while (true){
            if((num & 1) == 1){
                count++;
                num >>>= 1;//无符号右移1位
            }else{
                break;
            }
        }
        return Result.ok(count);
    }

    private User createUserWithPhone(String phone) {
        //创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        //保存用户，也是mybatis-plus的功能
        save(user);
        return user;
    }
}
