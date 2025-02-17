package com.github.zuihou.authority.service.auth.impl;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.zuihou.authority.dao.auth.MenuMapper;
import com.github.zuihou.authority.entity.auth.Menu;
import com.github.zuihou.authority.service.auth.MenuService;
import com.github.zuihou.authority.service.auth.ResourceService;
import com.github.zuihou.base.id.CodeGenerate;
import com.github.zuihou.common.constant.CacheKey;
import com.github.zuihou.database.mybatis.conditions.Wraps;
import com.github.zuihou.exception.BizException;
import com.github.zuihou.utils.NumberHelper;
import com.github.zuihou.utils.StrHelper;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import net.oschina.j2cache.CacheChannel;
import net.oschina.j2cache.CacheObject;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import static com.github.zuihou.utils.StrPool.DEF_PARENT_ID;

/**
 * <p>
 * 业务实现类
 * 菜单
 * </p>
 *
 * @author zuihou
 * @date 2019-07-03
 */
@Slf4j
@Service
public class MenuServiceImpl extends ServiceImpl<MenuMapper, Menu> implements MenuService {

    @Autowired
    private ResourceService resourceService;
    @Autowired
    private CacheChannel cache;
    @Autowired
    private CodeGenerate codeGenerate;

    /**
     * 查询用户可用菜单
     *
     * 注意：什么地方需要清除 USER_MENU 缓存
     * 给用户重新分配角色时， 角色重新分配资源/菜单时
     * @param group
     * @param userId
     * @return
     */
    @Override
    public List<Menu> findVisibleMenu(String group, Long userId) {
        String key = CacheKey.build(userId);
        CacheObject cacheObject = cache.get(CacheKey.USER_MENU, key, (k) -> {
            List<Menu> visibleMenu = baseMapper.findVisibleMenu(group, userId);
            return visibleMenu.stream().mapToLong(Menu::getId).boxed().collect(Collectors.toList());
        });

        if (cacheObject.getValue() == null) {
            return Collections.emptyList();
        }

        List<Long> list = (List<Long>) cacheObject.getValue();

        //使用 this::getByIdWithCache 会导致无法读取缓存
//        List<Menu> menuList = list.stream().map(this::getByIdWithCache).collect(Collectors.toList());
        List<Menu> menuList = list.stream().map(((MenuService) AopContext.currentProxy())::getByIdWithCache).collect(Collectors.toList());

        if (StrUtil.isEmpty(group)) {
            return menuList;
        }

        return menuList.stream().filter((menu) -> group.equals(menu.getGroup())).collect(Collectors.toList());
    }

    @Override
    @Cacheable(value = CacheKey.MENU, key = "#id")
    public Menu getByIdWithCache(Long id) {
        return super.getById(id);
    }

    @Override
    @CacheEvict(value = CacheKey.MENU, key = "#id")
    public boolean removeByIdWithCache(Long id) {
        return super.removeById(id);
    }

    @Override
    public boolean updateWithCache(Menu menu) {
        boolean result = super.updateById(menu);
        if (result) {
            // 修改冗余 菜单名称
            resourceService.updateMenuWithCache(menu.getId(), menu.getName());

            cache.evict(CacheKey.MENU, String.valueOf(menu.getId()));
        }
        return result;
    }

    @Override
    public boolean saveWithCache(Menu menu) {
        menu.setCode(StrHelper.getOrDef(menu.getCode(), codeGenerate.next()));
        if (super.count(Wraps.<Menu>lbQ().eq(Menu::getCode, menu.getCode())) > 0) {
            throw BizException.validFail("编码[%s]重复", menu.getCode());
        }

        menu.setIsEnable(NumberHelper.getOrDef(menu.getIsEnable(), true));
        menu.setIsPublic(NumberHelper.getOrDef(menu.getIsPublic(), false));
        menu.setParentId(NumberHelper.getOrDef(menu.getParentId(), DEF_PARENT_ID));

        super.save(menu);
        cache.set(CacheKey.MENU, String.valueOf(menu.getId()), menu);
        return true;
    }
}
