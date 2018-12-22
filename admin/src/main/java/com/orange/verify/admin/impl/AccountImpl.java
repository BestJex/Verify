package com.orange.verify.admin.impl;

import cn.hutool.core.util.StrUtil;
import com.alibaba.dubbo.config.annotation.Service;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.orange.verify.admin.mapper.AccountMapper;
import com.orange.verify.admin.mapper.CardMapper;
import com.orange.verify.admin.mapper.SoftMapper;
import com.orange.verify.admin.transition.Transition;
import com.orange.verify.api.bean.Account;
import com.orange.verify.api.bean.Card;
import com.orange.verify.api.bean.Soft;
import com.orange.verify.api.model.ServiceResult;
import com.orange.verify.api.service.AccountService;
import com.orange.verify.api.vo.AccountVo;
import com.orange.verify.api.vo.open.AccountLoginVo;
import com.orange.verify.api.vo.open.AccountRegisterVo;
import com.orange.verify.common.ip.BaiduIp;
import com.orange.verify.common.rsa.RsaUtil;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

@Service
public class AccountImpl extends ServiceImpl<AccountMapper, Account> implements AccountService {

    @Autowired
    private RedisImpl redis;

    @Autowired
    private SoftMapper softMapper;

    @Autowired
    private CardMapper cardMapper;

    @Autowired
    private Transition transition;

    @Override
    public Page<AccountVo> page(AccountVo accountVo, Page page) {

        return page.setRecords(super.baseMapper.page(accountVo,page));
    }

    @Override
    public ServiceResult<String> getPublicKey() {

        ServiceResult<String> result = new ServiceResult<>();

        //rsa
        String publicKeyToBase64 = null;
        String privateKeyToBase64 = null;
        try {
            Map<String, Object> initKey = RsaUtil.initKey();
            publicKeyToBase64 = RsaUtil.getPublicKeyToBase64(initKey);
            privateKeyToBase64 = RsaUtil.getPrivateKeyToBase64(initKey);
        } catch (Exception e) {
            result.setCode(3);
            return result;
        }

        redis.save10Minutes(publicKeyToBase64,privateKeyToBase64);

        result.setCode(1);
        result.setData(publicKeyToBase64);

        return result;
    }

    @Override
    public ServiceResult<Integer> register(AccountRegisterVo accountRegisterVo) {

        ServiceResult<Integer> result = new ServiceResult<>();

        String privateKey = (String)redis.getByKey(accountRegisterVo.getPublicKey());
        //钥匙不存在直接返回
        if (StrUtil.hasEmpty(privateKey)) {
            result.setCode(2);
            return result;
        }

        QueryWrapper<Account> username = new QueryWrapper<Account>().eq("username",
                accountRegisterVo.getUsername());
        Integer selectCount = super.baseMapper.selectCount(username);
        //用户名是否存在
        if (selectCount > 0) {
            result.setCode(6);
            return result;
        }

        Soft soft = softMapper.selectById(accountRegisterVo.getSoftId());
        if (soft == null) {
            result.setCode(3);
            return result;
        } else if (soft.getServiceStatus() == 2) {
            result.setCode(8);
            result.setMsg(soft.getServiceCloseMsg());
            return result;
        } else if (soft.getRegisterStatus() == 1) {
            result.setCode(9);
            result.setMsg(soft.getRegisteCloseMsg());
            return result;
        }

        //进行解密 >>> password 和 code >>> 解密成真实文本
        String password = null;
        try {
            password = RsaUtil.decodeRsa(accountRegisterVo.getPassword(), privateKey);
        } catch (Exception e) {
            result.setCode(5);
            return result;
        }
        if (StrUtil.hasEmpty(password)) {
            result.setCode(5);
            return result;
        } else if (password.length() > 10) {
            result.setCode(7);
            return result;
        }

        //查询ip信息
        String addressByIp = "";
        if (!"127.0.0.1".equals(accountRegisterVo.getIp())) {

            try {
                addressByIp = BaiduIp.start("m1ykK4CPuUVgZW3KDZO3lrvGzW2ZzYn6")
                        .getAddressByIp(accountRegisterVo.getIp());
            } catch (Exception e) {
                result.setCode(4);
                return result;
            }
        }

        //进行转型然后插入数据库
        accountRegisterVo.setPassword(password);

        Account account = transition.fromVo(accountRegisterVo);
        account.setCreateIpInfo(addressByIp);

        int insert = super.baseMapper.insert(account);

        result.setCode(1);
        result.setData(insert);

        return result;
    }

    @Override
    public ServiceResult<Integer> login(AccountLoginVo accountLoginVo) {

        ServiceResult<Integer> result = new ServiceResult<>();

        String privateKey = (String)redis.getByKey(accountLoginVo.getPublicKey());
        //钥匙不存在直接返回
        if (StrUtil.hasEmpty(privateKey)) {
            result.setCode(3);
            return result;
        }

        Soft soft = softMapper.selectById(accountLoginVo.getSoftId());
        //软件不存在直接返回
        if (soft == null) {
            result.setCode(4);
            return result;
        } else if (soft.getServiceStatus() == 2) {
            result.setCode(8);
            result.setMsg(soft.getServiceCloseMsg());
            return result;
        }

        //进行解密 >>> password 和 code >>> 解密成真实文本
        String password = null;
        String code = null;
        try {
            password = RsaUtil.decodeRsa(accountLoginVo.getPassword(), privateKey);
            code = RsaUtil.decodeRsa(accountLoginVo.getCode(), privateKey);
        } catch (Exception e) {
            result.setCode(5);
            return result;
        }
        if (StrUtil.hasEmpty(password,code)) {
            result.setCode(5);
            return result;
        } else if (password.length() > 10) {
            result.setCode(6);
            return result;
        }

        QueryWrapper<Account> queryWrapper = new QueryWrapper<Account>().eq("username",
                accountLoginVo.getUsername()).eq("password",password).eq("soft_id",accountLoginVo.getSoftId());

        //只支持单机 或者 是收费 进行机器码控制打开软件
        if (soft.getDosingStrategy() == 0 || soft.getServiceStatus() == 0) {
            queryWrapper = queryWrapper.eq("code",code);
        }
        Account account = super.baseMapper.selectOne(queryWrapper);
        if (account != null) {
            Card card = null;
            if (soft.getServiceStatus() == 0) {
                String cardId = account.getCardId();
                if (StrUtil.hasEmpty(cardId)) {
                    result.setCode(9);
                    return result;
                }
                card = cardMapper.selectById(cardId);
                if (card == null) {
                    result.setCode(9);
                    return result;
                } else if (card.getClosure() == 1) {
                    result.setCode(10);
                    return result;
                }
                long totalTime = card.getEndDate() - System.currentTimeMillis();
                if (totalTime < 1) {
                    result.setCode(11);
                    return result;
                }
            }
            result.setCode(1);
            result.setData(card.getEndDate());
            return result;
        }
        result.setCode(2);
        return result;
    }

}
