package com.xxxx.seckill.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wf.captcha.ArithmeticCaptcha;
import com.xxxx.seckill.config.AccessLimit;
import com.xxxx.seckill.exception.GlobalException;
import com.xxxx.seckill.pojo.Order;
import com.xxxx.seckill.pojo.SeckillMessage;
import com.xxxx.seckill.pojo.SeckillOrder;
import com.xxxx.seckill.pojo.User;
import com.xxxx.seckill.rabbitmq.MQSender;
import com.xxxx.seckill.service.IGoodsService;
import com.xxxx.seckill.service.IOrderService;
import com.xxxx.seckill.service.ISeckillOrderService;
import com.xxxx.seckill.vo.GoodsVo;
import com.xxxx.seckill.vo.RespBean;
import com.xxxx.seckill.vo.RespBeanEnum;
import com.xxxx.seckill.utils.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀功能控制器
 * - 商品库存够不够？
 * - 每人只能秒一件商品，防止黄牛
 */
@Slf4j
@Controller
@RequestMapping("/seckill")
public class SecKillController implements InitializingBean {

    @Autowired
    private IGoodsService goodsService;
    @Autowired
    private ISeckillOrderService seckillOrderService;
    @Autowired
    private IOrderService orderService;
    @Autowired
    private RedisTemplate redisTemplate;
    @Autowired
    private MQSender mqSender;
    //内存标记优化性能，如果商品早就卖完了，新过来的请求仍需与Redis交互，浪费内存
    private Map<Long,Boolean> EmptyStockMap = new HashMap<>();
    @Autowired
    private RedisScript<Long> script;

    /**
     * 秒杀功能优化前
     * windows下压力测试 优化前：1061.7
     * @param model
     * @param user
     * @param goodsId
     * @return
     */
    @RequestMapping( "/doSeckill_v0")
    public String doSeckill_v0(Model model, User user,Long goodsId){
        if(user==null){
            return "login";
        }
        model.addAttribute("user",user);
        GoodsVo goods = goodsService.findGoodsVoByGoodsId(goodsId);
        //判断商品库存，查询而不是依靠前端，防止作假
        if(goods.getStockCount()<1){
            model.addAttribute("errmsg", RespBeanEnum.EMPTY_STOCK.getMessage());
            //返回秒杀失败页面
            return "secKillFail";
        }
        //判断是否重复购买
        SeckillOrder seckillOrder = seckillOrderService.getOne(
                new QueryWrapper<SeckillOrder>()
                        .eq("user_id", user.getId())
                        .eq("goods_id", goodsId));
        if(seckillOrder!=null){
            model.addAttribute("errmsg",RespBeanEnum.REPEATE_ERROR.getMessage());
            return "secKillFail";
        }
        Order order = orderService.seckill(user,goods);
        model.addAttribute("order",order);
        model.addAttribute("goods",goods);
        return "orderDetail";
    }

    /**
     * 秒杀功能做页面静态化优化后
     * windows下压力测试 优化后：2212.0
     * @param model
     * @param user
     * @param goodsId
     * @return
     */
    @RequestMapping( value = "/doSeckill_v1",method = RequestMethod.POST)
    @ResponseBody
    public RespBean doSeckill_v1(Model model, User user, Long goodsId){
        if(user==null){
            return RespBean.error(RespBeanEnum.SESSION_ERROR);
        }

        GoodsVo goods = goodsService.findGoodsVoByGoodsId(goodsId);
        //判断商品库存，查询而不是依靠前端，防止作假
        if(goods.getStockCount()<1){

            //返回秒杀失败页面
            return RespBean.error(RespBeanEnum.EMPTY_STOCK);
        }
        //判断是否重复购买
//        SeckillOrder seckillOrder = seckillOrderService.getOne(
//                new QueryWrapper<SeckillOrder>()
//                        .eq("user_id", user.getId())
//                        .eq("goods_id", goodsId));

        //从Redis中获取订单信息，判断是否重复购买，优化性能
        SeckillOrder seckillOrder = (SeckillOrder) redisTemplate.opsForValue().get("order:" + user.getId() + ":" + goodsId);
        if(seckillOrder!=null){
            return RespBean.error(RespBeanEnum.REPEATE_ERROR);
        }
        Order order = orderService.seckill(user,goods);

        return RespBean.success(order);
    }

    /**
     * Redis预减库存、内存标记、队列缓冲优化后
     * Windows下压力测试 优化后：3184.5
     * @param user
     * @param goodsId
     * @return
     */
    @RequestMapping( value = "/doSeckill_v2",method = RequestMethod.POST)
    @ResponseBody
    public RespBean doSeckill_v2(User user, Long goodsId){
        //判断是否未登录
        if(user==null){
            return RespBean.error(RespBeanEnum.SESSION_ERROR);
        }
        ValueOperations valueOperations = redisTemplate.opsForValue();
        //从Redis中获取订单信息，判断是否重复购买，优化性能
        SeckillOrder seckillOrder = (SeckillOrder) redisTemplate.opsForValue().get("order:" + user.getId() + ":" + goodsId);
        if(seckillOrder!=null){
            return RespBean.error(RespBeanEnum.REPEATE_ERROR);
        }
        //通过内存标记，减少Redis的访问
        if(EmptyStockMap.get(goodsId)){
            return RespBean.error(RespBeanEnum.EMPTY_STOCK);
        }
        //redis预减库存，decrement是递减操作，原子性
        Long stock = valueOperations.decrement("seckillGoods:" + goodsId);
        //预减后库存小于零，返回库存不足
        if(stock<0){
            //记得将空库存的商品进行标记
            EmptyStockMap.put(goodsId,true);
            //避免库存为负数
            valueOperations.increment("seckillGoods:" + goodsId);
            return RespBean.error(RespBeanEnum.EMPTY_STOCK);
        }
        //封装秒杀信息
        SeckillMessage seckillMessage = new SeckillMessage(user, goodsId);
        //发送到消息队列中
        mqSender.sendSeckillMessage(JsonUtil.object2JsonStr(seckillMessage));
        //返回0则前端处理为“排队中”，究竟是否下单成功需要进一步处理
        //因为使用了rabbitmq，变为异步操作，可以快速返回，达到流量削峰的作用
        return RespBean.success(0);
    }

    /**
     * lua+Redis实现分布式锁
     * @param user
     * @param goodsId
     * @return
     */
    @RequestMapping( value = "/doSeckill_v3",method = RequestMethod.POST)
    @ResponseBody
    public RespBean doSeckill_v3(User user, Long goodsId){
        //判断是否未登录
        if(user==null){
            return RespBean.error(RespBeanEnum.SESSION_ERROR);
        }
        ValueOperations valueOperations = redisTemplate.opsForValue();
        //从Redis中获取订单信息，判断是否重复购买，优化性能
        SeckillOrder seckillOrder = (SeckillOrder) redisTemplate.opsForValue().get("order:" + user.getId() + ":" + goodsId);
        if(seckillOrder!=null){
            return RespBean.error(RespBeanEnum.REPEATE_ERROR);
        }
        //通过内存标记，减少Redis的访问
        if(EmptyStockMap.get(goodsId)){
            return RespBean.error(RespBeanEnum.EMPTY_STOCK);
        }
        //不再使用decrement来递减库存，而是使用lua脚本
        Long stock = (Long) redisTemplate.execute(script, Collections.singletonList("seckillGoods:" + goodsId), Collections.EMPTY_LIST);
        //预减后库存小于零，返回库存不足
        if(stock<0){
            //记得将空库存的商品进行标记
            EmptyStockMap.put(goodsId,true);
            //因为已经使用了lua，库存为负时只会是-1
            //valueOperations.increment("seckillGoods:" + goodsId);
            return RespBean.error(RespBeanEnum.EMPTY_STOCK);
        }
        //封装秒杀信息
        SeckillMessage seckillMessage = new SeckillMessage(user, goodsId);
        //发送到消息队列中
        mqSender.sendSeckillMessage(JsonUtil.object2JsonStr(seckillMessage));
        //返回0则前端处理为“排队中”，究竟是否下单成功需要进一步处理
        //因为使用了rabbitmq，变为异步操作，可以快速返回，达到流量削峰的作用
        return RespBean.success(0);
    }

    /**
     * 安全优化：隐藏秒杀接口
     * @param path
     * @param user
     * @param goodsId
     * @return
     */
    @RequestMapping( value = "/{path}/doSeckill_v4",method = RequestMethod.POST)
    @ResponseBody
    public RespBean doSeckill_v4(@PathVariable String path, User user, Long goodsId){
        //判断是否未登录
        if(user==null){
            return RespBean.error(RespBeanEnum.SESSION_ERROR);
        }
        ValueOperations valueOperations = redisTemplate.opsForValue();
        //校验是否与"某个用户对应某个商品的唯一秒杀地址"相同
        boolean check = orderService.checkPath(user,goodsId,path);
        if(!check){
            return RespBean.error(RespBeanEnum.REQUEST_ILLEGAL);
        }
        //从Redis中获取订单信息，判断是否重复购买，优化性能
        SeckillOrder seckillOrder = (SeckillOrder) redisTemplate.opsForValue().get("order:" + user.getId() + ":" + goodsId);
        if(seckillOrder!=null){
            return RespBean.error(RespBeanEnum.REPEATE_ERROR);
        }
        //通过内存标记，减少Redis的访问
        if(EmptyStockMap.get(goodsId)){
            return RespBean.error(RespBeanEnum.EMPTY_STOCK);
        }
        //不再使用decrement来递减库存，而是使用lua脚本
        Long stock = (Long) redisTemplate.execute(script, Collections.singletonList("seckillGoods:" + goodsId), Collections.EMPTY_LIST);
        //预减后库存小于零，返回库存不足
        if(stock<0){
            //记得将空库存的商品进行标记
            EmptyStockMap.put(goodsId,true);
            //因为已经使用了lua，库存为负时只会是-1
            //valueOperations.increment("seckillGoods:" + goodsId);
            return RespBean.error(RespBeanEnum.EMPTY_STOCK);
        }
        //封装秒杀信息
        SeckillMessage seckillMessage = new SeckillMessage(user, goodsId);
        //发送到消息队列中
        mqSender.sendSeckillMessage(JsonUtil.object2JsonStr(seckillMessage));
        //返回0则前端处理为“排队中”，究竟是否下单成功需要进一步处理
        //因为使用了rabbitmq，变为异步操作，可以快速返回，达到流量削峰的作用
        return RespBean.success(0);
    }

    /**
     * 安全优化：加入验证码校验
     * @param path
     * @param user
     * @param goodsId
     * @return
     */
    @RequestMapping( value = "/{path}/doSeckill",method = RequestMethod.POST)
    @ResponseBody
    public RespBean doSeckill(@PathVariable String path, User user, Long goodsId){
        //判断是否未登录
        if(user==null){
            return RespBean.error(RespBeanEnum.SESSION_ERROR);
        }
        ValueOperations valueOperations = redisTemplate.opsForValue();
        //校验是否与"某个用户对应某个商品的唯一秒杀地址"相同
        boolean check = orderService.checkPath(user,goodsId,path);
        if(!check){
            return RespBean.error(RespBeanEnum.REQUEST_ILLEGAL);
        }
        //从Redis中获取订单信息，判断是否重复购买，优化性能
        SeckillOrder seckillOrder = (SeckillOrder) redisTemplate.opsForValue().get("order:" + user.getId() + ":" + goodsId);
        if(seckillOrder!=null){
            return RespBean.error(RespBeanEnum.REPEATE_ERROR);
        }
        //通过内存标记，减少Redis的访问
        if(EmptyStockMap.get(goodsId)){
            return RespBean.error(RespBeanEnum.EMPTY_STOCK);
        }
        //不再使用decrement来递减库存，而是使用lua脚本
        Long stock = (Long) redisTemplate.execute(script, Collections.singletonList("seckillGoods:" + goodsId), Collections.EMPTY_LIST);
        //预减后库存小于零，返回库存不足
        if(stock<0){
            //记得将空库存的商品进行标记
            EmptyStockMap.put(goodsId,true);
            //因为已经使用了lua，库存为负时只会是-1
            //valueOperations.increment("seckillGoods:" + goodsId);
            return RespBean.error(RespBeanEnum.EMPTY_STOCK);
        }
        //封装秒杀信息
        SeckillMessage seckillMessage = new SeckillMessage(user, goodsId);
        //发送到消息队列中
        mqSender.sendSeckillMessage(JsonUtil.object2JsonStr(seckillMessage));
        //返回0则前端处理为“排队中”，究竟是否下单成功需要进一步处理
        //因为使用了rabbitmq，变为异步操作，可以快速返回，达到流量削峰的作用
        return RespBean.success(0);
    }

    /**
     * 获取秒杀结果
     * @param user
     * @param goodsId
     * @return orderId则成功，-1则失败，0排队中
     */
    @RequestMapping(value = "/result",method = RequestMethod.GET)
    @ResponseBody
    public RespBean getResult(User user,Long goodsId){
        if(user==null){
            return RespBean.error(RespBeanEnum.SESSION_ERROR);
        }
        Long orderId = seckillOrderService.getResult(user,goodsId);
        return RespBean.success(orderId);
    }

    /**
     * 获取秒杀地址
     * 针对不同用户秒杀不同商品，生成不同地址，防止提前得知秒杀接口地址使用脚本
     * @param user
     * @param goodsId
     * @return
     */
    @RequestMapping(value = "/path_v0",method = RequestMethod.GET)
    @ResponseBody
    public RespBean getPath_v0(User user,Long goodsId){
        if(user==null){
            return RespBean.error(RespBeanEnum.SESSION_ERROR);
        }
        String str = orderService.createPath(user,goodsId);
        return RespBean.success(str);
    }

    /**
     * 加入了校验验证码步骤，
     * @param user
     * @param goodsId
     * @return
     */
    @RequestMapping(value = "/path_v1",method = RequestMethod.GET)
    @ResponseBody
    public RespBean getPath_v1(User user,Long goodsId,String captcha){
        if(user==null){
            return RespBean.error(RespBeanEnum.SESSION_ERROR);
        }
        boolean check = orderService.checkCaptha(user,goodsId,captcha);
        if(!check){
            return RespBean.error(RespBeanEnum.ERROR_CAPTCHA);
        }
        String str = orderService.createPath(user,goodsId);
        return RespBean.success(str);
    }

    /**
     * 加入限流功能，避免短时间内同一用户多次请求
     * @param user
     * @param goodsId
     * @param captcha
     * @return
     */
    @RequestMapping(value = "/path_v2",method = RequestMethod.GET)
    @ResponseBody
    public RespBean getPath_v2(User user, Long goodsId, String captcha, HttpServletRequest request){
        if(user==null){
            return RespBean.error(RespBeanEnum.SESSION_ERROR);
        }
        ValueOperations valueOperations = redisTemplate.opsForValue();
        String uri = request.getRequestURI();
        Integer count = (Integer) valueOperations.get(uri + ":" + user.getId());
        if(count==null){
            valueOperations.set(uri+":"+user.getId(),1,5,TimeUnit.SECONDS);
        }else if(count<5){
            valueOperations.increment(uri+":"+user.getId());
        }else{
            return RespBean.error(RespBeanEnum.ACCESS_LIMIT);
        }
        boolean check = orderService.checkCaptha(user,goodsId,captcha);
        if(!check){
            return RespBean.error(RespBeanEnum.ERROR_CAPTCHA);
        }
        String str = orderService.createPath(user,goodsId);
        return RespBean.success(str);
    }

    /**
     * 通过注解功能实现限流，增加通用性
     * @param user
     * @param goodsId
     * @param captcha
     * @param request
     * @return
     */
    @AccessLimit(second=5,maxCount=5,needLogin=true)
    @RequestMapping(value = "/path",method = RequestMethod.GET)
    @ResponseBody
    public RespBean getPath(User user, Long goodsId, String captcha, HttpServletRequest request){
        if(user==null){
            return RespBean.error(RespBeanEnum.SESSION_ERROR);
        }
        boolean check = orderService.checkCaptha(user,goodsId,captcha);
        if(!check){
            return RespBean.error(RespBeanEnum.ERROR_CAPTCHA);
        }
        String str = orderService.createPath(user,goodsId);
        return RespBean.success(str);
    }

    /**
     * 生成验证码
     * @param user
     * @param goodsId
     * @param response
     */
    @RequestMapping(value = "/captcha",method = RequestMethod.GET)
    public void captcha(User user, Long goodsId, HttpServletResponse response){
        if(user==null||goodsId<0){
            throw new GlobalException(RespBeanEnum.REQUEST_ILLEGAL);
        }
        //设置请求头输出为图片形式，不缓存，无失效时间
        response.setContentType("image/jpg");
        response.setHeader("Pargam","No-cache");
        response.setHeader("Cache-Control","no-cache");
        response.setDateHeader("Expires",0);
        //生成验证码并放入Redis
        ArithmeticCaptcha captcha = new ArithmeticCaptcha(130, 32, 3);
        redisTemplate.opsForValue().set("captcha:"+user.getId()+":"+goodsId,captcha.text(),300, TimeUnit.SECONDS);
        try {
            captcha.out(response.getOutputStream());
        } catch (IOException e) {
            log.error("验证码生成失败",e.getMessage());
        }

    }

    /**
     * 系统初始化时，加载秒杀商品的库存到Redis中
     * @throws Exception
     */
    @Override
    public void afterPropertiesSet() throws Exception {
        List<GoodsVo> list = goodsService.findGoodsVo();
        if(CollectionUtils.isEmpty(list)){
            return;
        }
        list.forEach(goodsVo -> {
            redisTemplate.opsForValue().set("seckillGoods:"+goodsVo.getId(),goodsVo.getStockCount());
            EmptyStockMap.put(goodsVo.getId(),false);
        });

    }
}
