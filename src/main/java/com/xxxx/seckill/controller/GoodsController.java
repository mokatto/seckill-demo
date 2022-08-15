package com.xxxx.seckill.controller;

import com.xxxx.seckill.pojo.User;
import com.xxxx.seckill.service.IGoodsService;
import com.xxxx.seckill.service.IUserService;
import com.xxxx.seckill.vo.DetailVo;
import com.xxxx.seckill.vo.GoodsVo;
import com.xxxx.seckill.vo.RespBean;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring5.view.ThymeleafViewResolver;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Date;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Controller
@RequestMapping("/goods")
public class GoodsController {

    @Autowired
    private IUserService userService;
    @Autowired
    private IGoodsService goodsService;
    @Autowired
    private RedisTemplate redisTemplate;
    @Autowired
    private ThymeleafViewResolver thymeleafViewResolver;


    /**
     * 跳转到商品页
     * @param request
     * @param response
     * @param model
     * @param ticket
     * @return
     * 存在session中*/
    @RequestMapping("/toList_v0")
    public String toList_v0(HttpServletRequest request, HttpServletResponse response, Model model, @CookieValue("userTicket") String ticket){
        if(StringUtils.isEmpty(ticket)){
            return "login";
        }
        //User user = (User) session.getAttribute(ticket);
        User user = userService.getUserByCookie(ticket,request, response);
        if(null==user){
            return "login";
        }
        model.addAttribute("user",user);
        return "goodsList";
    }

    /**
     * windows下压力测试，优化前：4878.7
     * 页面缓存前
     * @param user
     * @param model
     * @return
     */
    @RequestMapping("/toList_v1")
    public String toList_v1(User user,Model model){

        /*if(null==user){
            return "login";
        }*/
        if(Objects.isNull(user)){
            return "login";
        }
        model.addAttribute("user",user);
        model.addAttribute("goodsList",goodsService.findGoodsVo());
        return "goodsList";
    }

    /**
     * 做了页面缓存优化后
     * windows下压力测试：6290.1
     * @param user
     * @param model
     * @return
     */
    @RequestMapping(value = "/toList",produces = "text/html;charset=utf-8")
    @ResponseBody
    public String toList(User user,Model model,HttpServletRequest request,HttpServletResponse response){
        if(Objects.isNull(user)){
            return "login";
        }
        //redis中获取页面，不为空则直接返回
        ValueOperations valueOperations = redisTemplate.opsForValue();
        String html = (String) valueOperations.get("goodsList");
        if(!StringUtils.isEmpty(html)){
            return html;
        }
        //如果为空，需要手动渲染thymeleaf，放入缓存Redis并返回
        model.addAttribute("user",user);
        model.addAttribute("goodsList",goodsService.findGoodsVo());
        WebContext context = new WebContext(request, response, request.getServletContext(), request.getLocale(), model.asMap());
        html = thymeleafViewResolver.getTemplateEngine().process("goodsList", context);
        if(!StringUtils.isEmpty(html)){
            //设置失效时间，因为会优先读取缓存，如果不设置失效，则页面不会变更
            valueOperations.set("goodsList",html,60, TimeUnit.SECONDS);
        }
        return html;
    }


    /**
     * 初始商品详情页面
     * @param model
     * @param user
     * @param goodsId
     * @return
     */
    @RequestMapping("/toDetail_v0/{goodsId}")
    public String toDetail_v0(Model  model,User user, @PathVariable Long goodsId){
        model.addAttribute("user",user);
        GoodsVo goodsVo = goodsService.findGoodsVoByGoodsId(goodsId);
        Date startDate = goodsVo.getStartDate();
        Date endDate = goodsVo.getEndDate();
        Date date = new Date();
        System.out.println("开始时间："+startDate);
        System.out.println("现在时间："+date);
        System.out.println("结束时间："+endDate);
        int secKillStatus=0; //秒杀状态
        int remainSeconds=0; //秒杀倒计时
        if(date.before(startDate)){
            remainSeconds=(int) (startDate.getTime()-date.getTime())/1000;
        }else if(date.after(endDate)){
            secKillStatus=2;
            remainSeconds=-1;
        }else{
            secKillStatus=1;
            remainSeconds=0;
        }
        model.addAttribute("remainSeconds",remainSeconds);
        model.addAttribute("secKillStatus",secKillStatus);
        model.addAttribute("goods",goodsVo);
        return "goodsDetail";
    }

    /**
     * 商品详情页面，做URL缓存
     * @param model
     * @param user
     * @param goodsId
     * @param request
     * @param response
     * @return
     */
    @RequestMapping(value = "/toDetail_v1/{goodsId}",produces = "text/html;charset=utf-8")
    @ResponseBody
    public String toDetail_v1(Model  model,User user, @PathVariable Long goodsId,HttpServletRequest request,HttpServletResponse response){
        //从Redis中获取页面
        ValueOperations valueOperations = redisTemplate.opsForValue();
        String html = (String) valueOperations.get("goodsDetail:" + goodsId);
        //如果不为空直接返回
        if(!StringUtils.isEmpty(html)){
            return html;
        }
        model.addAttribute("user",user);
        GoodsVo goodsVo = goodsService.findGoodsVoByGoodsId(goodsId);
        Date startDate = goodsVo.getStartDate();
        Date endDate = goodsVo.getEndDate();
        Date date = new Date();
        System.out.println("开始时间："+startDate);
        System.out.println("现在时间："+date);
        System.out.println("结束时间："+endDate);
        int secKillStatus=0; //秒杀状态
        int remainSeconds=0; //秒杀倒计时
        if(date.before(startDate)){
            remainSeconds=(int) (startDate.getTime()-date.getTime())/1000;
        }else if(date.after(endDate)){
            secKillStatus=2;
            remainSeconds=-1;
        }else{
            secKillStatus=1;
            remainSeconds=0;
        }
        model.addAttribute("remainSeconds",remainSeconds);
        model.addAttribute("secKillStatus",secKillStatus);
        model.addAttribute("goods",goodsVo);
        WebContext webContext = new WebContext(request, response, request.getServletContext(), request.getLocale(),model.asMap());
        html= thymeleafViewResolver.getTemplateEngine().process("goodsDetail", webContext);
        if(!StringUtils.isEmpty(html)){
            valueOperations.set("goodsDetail:"+goodsId,html,60,TimeUnit.SECONDS);
        }
        return html;
    }


    @RequestMapping("/detail/{goodsId}")
    @ResponseBody
    public RespBean toDetail(User user, @PathVariable Long goodsId){

        GoodsVo goodsVo = goodsService.findGoodsVoByGoodsId(goodsId);
        Date startDate = goodsVo.getStartDate();
        Date endDate = goodsVo.getEndDate();
        Date date = new Date();
        int secKillStatus=0; //秒杀状态
        int remainSeconds=0; //秒杀倒计时
        if(date.before(startDate)){
            remainSeconds=(int) (startDate.getTime()-date.getTime())/1000;
        }else if(date.after(endDate)){
            secKillStatus=2;
            remainSeconds=-1;
        }else{
            secKillStatus=1;
            remainSeconds=0;
        }
        DetailVo detailVo = new DetailVo();
        detailVo.setUser(user);
        detailVo.setGoodsVo(goodsVo);
        detailVo.setSecKillStatus(secKillStatus);
        detailVo.setRemainSeconds(remainSeconds);
        return RespBean.success(detailVo);
    }

}
