package org.sam.shen.scheduing.scheduler;

import java.util.*;
import java.util.stream.Stream;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import com.google.common.base.Splitter;
import com.google.common.collect.Maps;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.quartz.impl.matchers.GroupMatcher;
import org.sam.shen.core.constants.Constant;
import org.sam.shen.core.constants.EventStatus;
import org.sam.shen.scheduing.entity.JobEvent;
import org.sam.shen.scheduing.entity.JobInfo;
import org.sam.shen.scheduing.mapper.JobEventMapper;
import org.sam.shen.scheduing.mapper.JobInfoMapper;
import org.sam.shen.scheduing.service.RedisService;
import org.sam.shen.scheduing.vo.SchedulerJobVo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import com.google.common.collect.Lists;

@Component
public final class RadishDynamicScheduler implements ApplicationContextAware {
	private static final Logger logger = LoggerFactory.getLogger(RadishDynamicScheduler.class);
	
	// scheduler
	private static Scheduler scheduler;
	
	public static JobInfoMapper jobInfoMapper;
	
	public static JobEventMapper jobEventMapper;
	
	public static RedisService redisService;
	
	private RadishDynamicScheduler() {
		super();
	}
	
	@Override
	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		RadishDynamicScheduler.scheduler = applicationContext.getBean("quartzScheduler", Scheduler.class);
		RadishDynamicScheduler.jobInfoMapper = applicationContext.getBean(JobInfoMapper.class);
		RadishDynamicScheduler.redisService = applicationContext.getBean(RedisService.class);
		RadishDynamicScheduler.jobEventMapper = applicationContext.getBean(JobEventMapper.class);
	}
	
	@PostConstruct
	public void init() throws Exception {
		// valid
		Assert.notNull(scheduler, "quartz scheduler is null");
		logger.info(">>>>>>>>> init job-scheduler success.");
	}
	
	@PreDestroy
	public void destroy() {
		// TODO
	}
	
	/**
	 *  添加Job任务
	 * @author suoyao
	 * @date 下午5:52:11
	 * @param jobId
	 * @param jobName
	 * @param crontab
	 * @return
	 * @throws SchedulerException
	 */
	public static boolean addJob(final Long jobId, final String jobName, final String crontab) throws SchedulerException {
		// TriggerKey valid if_exists
		if (checkExists(jobId, jobName)) {
			logger.info(">>>>>>>>> addJob fail, job already exist, jobGroup:{}, jobName:{}", jobId, jobName);
			return false;
		}
		
		// CronTrigger : TriggerKey + crontab //
		// withMisfireHandlingInstructionDoNothing 忽略掉调度终止过程中忽略的调度
		TriggerKey triggerKey = getTriggerKey(jobId, jobName);
		CronScheduleBuilder cronScheduleBuilder = CronScheduleBuilder.cronSchedule(crontab)
		        .withMisfireHandlingInstructionDoNothing();
		CronTrigger cronTrigger = TriggerBuilder.newTrigger().withIdentity(triggerKey).withSchedule(cronScheduleBuilder)
		        .build();

		// Trigger the job to run with cron
		JobKey jobKey = new JobKey(String.valueOf(jobId), jobName);
		Class<? extends Job> jobClass_ = EventJobBean.class;
		JobDataMap jobDataMap = new JobDataMap(new HashMap<String, Long>(){
			private static final long serialVersionUID = 1L;
			{
				put("jobId", jobId);
			}
		}) ;
		JobDetail jobDetail = JobBuilder.newJob(jobClass_).withIdentity(jobKey).usingJobData(jobDataMap).build();
		
		// Tell quartz to schedule the job using our trigger
		Date date = scheduler.scheduleJob(jobDetail, cronTrigger);
		if(logger.isInfoEnabled()) {
			logger.info(">>>>>>>>>>> addJob success, jobDetail:{}, cronTrigger:{}, date:{}", jobDetail, cronTrigger, date);
		}
		return true;
	}
	
	/**
	 *  更新Schedule Job
	 * @author suoyao
	 * @date 下午5:11:52
	 * @param jobId
	 * @param jobName
	 * @param crontab
	 * @return
	 * @throws SchedulerException
	 */
	public static boolean UpgradeScheduleJob(final Long jobId, final String jobName, final String crontab) throws SchedulerException {
		// TriggerKey valid if_exists
		if (!checkExists(jobId, jobName)) {
			logger.error(">>>>>>>>>>> Upgrade ScheduleJob, job not exists, JobGroup:{}, JobName:{}", jobId, jobName);
			addJob(jobId, jobName, crontab);
			return true;
		}
		// TriggerKey : name + group
		TriggerKey triggerKey = getTriggerKey(jobId, jobName);
		CronTrigger oldTrigger = (CronTrigger) scheduler.getTrigger(triggerKey);
		if(null != oldTrigger) {
			// 存在旧的触发器
			// avoid repeat
			String oldCron = oldTrigger.getCronExpression();
			if (oldCron.equals(crontab)) {
				return true;
			}
			// CronTrigger : TriggerKey + crontab
			CronScheduleBuilder cronScheduleBuilder = CronScheduleBuilder.cronSchedule(crontab)
			        .withMisfireHandlingInstructionDoNothing();
			oldTrigger = oldTrigger.getTriggerBuilder().withIdentity(triggerKey).withSchedule(cronScheduleBuilder)
			        .build();

			// rescheduleJob
			scheduler.rescheduleJob(triggerKey, oldTrigger);
		} else {
			// CronTrigger : TriggerKey + crontab
			CronScheduleBuilder cronScheduleBuilder = CronScheduleBuilder.cronSchedule(crontab)
			        .withMisfireHandlingInstructionDoNothing();
			CronTrigger cronTrigger = TriggerBuilder.newTrigger().withIdentity(triggerKey)
			        .withSchedule(cronScheduleBuilder).build();

			// JobDetail-JobDataMap fresh
			JobKey jobKey = new JobKey(String.valueOf(jobId), jobName);
			JobDetail jobDetail = scheduler.getJobDetail(jobKey);

			// Trigger fresh
			HashSet<Trigger> triggerSet = new HashSet<Trigger>();
			triggerSet.add(cronTrigger);

			scheduler.scheduleJob(jobDetail, triggerSet, true);
		}
		if(logger.isInfoEnabled()) {
			logger.info(">>>>>>>>>>> resumeJob success, JobGroup:{}, JobName:{}", jobId, jobName);
		}
		return true;
	}
	
	/**
	 * 移除 schedule Job
	 * @author suoyao
	 * @date 下午5:13:37
	 * @param jobId
	 * @param jobName
	 * @return
	 * @throws SchedulerException
	 */
	public static boolean removeJob(final Long jobId, final String jobName) throws SchedulerException {
		// TriggerKey : name + group
		TriggerKey triggerKey = getTriggerKey(jobId, jobName);
		boolean result = false;
		if (checkExists(jobId, jobName)) {
			result = scheduler.unscheduleJob(triggerKey);
			logger.info(">>>>>>>>>>> removeJob, triggerKey:{}, result [{}]", triggerKey, result);
		}
		return true;
	}
	
	/**
	 *  暂停Job任务
	 * @author suoyao
	 * @date 下午5:27:25
	 * @param jobId
	 * @param jobName
	 * @return
	 * @throws SchedulerException
	 */
	public static boolean pauseJob(final Long jobId, final String jobName) throws SchedulerException {
		// TriggerKey : name + group
		TriggerKey triggerKey = getTriggerKey(jobId, jobName);
		boolean result = false;
		if (checkExists(jobId, jobName)) {
			scheduler.pauseTrigger(triggerKey);
			result = true;
			logger.info(">>>>>>>>>>> pauseJob success, triggerKey:{}", triggerKey);
		} else {
			logger.info(">>>>>>>>>>> pauseJob fail, triggerKey:{}", triggerKey);
		}
		return result;
	}
    
	/**
	 *  重启Job任务
	 * @author suoyao
	 * @date 下午5:27:07
	 * @param jobId
	 * @param jobName
	 * @return
	 * @throws SchedulerException
	 */
	public static boolean resumeJob(final Long jobId, final String jobName) throws SchedulerException {
		// TriggerKey : name + group
		TriggerKey triggerKey = getTriggerKey(jobId, jobName);

		boolean result = false;
		if (checkExists(jobId, jobName)) {
			scheduler.resumeTrigger(triggerKey);
			result = true;
			logger.info(">>>>>>>>>>> resumeJob success, triggerKey:{}", triggerKey);
		} else {
			logger.info(">>>>>>>>>>> resumeJob fail, triggerKey:{}", triggerKey);
		}
		return result;
	}
	
	public static boolean checkExists(final Long jobId, final String jobName) throws SchedulerException{
		TriggerKey triggerKey = getTriggerKey(jobId, jobName);
		return scheduler.checkExists(triggerKey);
	}
	
	private static TriggerKey getTriggerKey(final Long jobId, final String jobName) {
		return TriggerKey.triggerKey(String.valueOf(jobId), jobName);
	}
	
	@SuppressWarnings("unchecked")
	public static List<SchedulerJobVo> listJobsInScheduler() throws SchedulerException {
		List<SchedulerJobVo> list = Lists.newArrayList();
		for(String groupName: scheduler.getJobGroupNames()) {
		    // enumerate each job in group
		    for(JobKey jobKey : scheduler.getJobKeys(GroupMatcher.jobGroupEquals(groupName))) {
		        System.out.println("Found job identified by: " + jobKey);
		        System.out.println("jobName is : " + jobKey.getName());
		        System.out.println("jobGroup is : " + jobKey.getName());
		        SchedulerJobVo vo = new SchedulerJobVo(jobKey.getName(), jobKey.getGroup());
		        List<CronTrigger> triggers = (List<CronTrigger>) scheduler.getTriggersOfJob(jobKey);
		        if(null != triggers && triggers.size() > 0) {
		        	vo.setCrontab(triggers.get(0).getCronExpression());
		        	vo.setPrevFireTime(triggers.get(0).getPreviousFireTime());
		        	vo.setNextFireTime(triggers.get(0).getNextFireTime());
		        		 }
		        list.add(vo);
		    }
		}
		return list;
	}

    /**
     * Add job event
     * @author clock
     * @date 2018/12/4 下午3:23
     * @param jobInfo job information
     * @return add result
     */
	public static boolean addJobEvent(JobInfo jobInfo) {
        List<String> agentHandlers = Splitter.onPattern(",|-").splitToList(jobInfo.getExecutorHandlers());
        Map<String, Object> eventHash = Maps.newHashMap();
        eventHash.put("priority", jobInfo.getPriority());    // 设置优先级
        Stream.iterate(0, i -> i + 1).limit(agentHandlers.size()).forEach(i -> {
            if(i % 2 == 0) {
                if(eventHash.containsKey(agentHandlers.get(i))) {
                    String val = String.valueOf(eventHash.get(agentHandlers.get(i))).concat(",").concat(agentHandlers.get(i + 1));
                    eventHash.put(agentHandlers.get(i), val);
                } else {
                    eventHash.put(agentHandlers.get(i), agentHandlers.get(i + 1));
                }
            }
        });
        JobEvent jobEvent = new JobEvent(jobInfo.getId(), jobInfo.getExecutorHandlers(), jobInfo.getHandlerType(),
                EventStatus.READY, jobInfo.getPriority(), jobInfo.getCmd(), jobInfo.getParams());
        jobEvent.setParentJobId(jobInfo.getParentJobId());
        jobEventMapper.saveJobEvent(jobEvent);

        redisService.hmset(Constant.REDIS_EVENT_PREFIX.concat(jobEvent.getEventId()),
                eventHash);
        return true;
    }

    /**
     * Add job event
     * @author clock
     * @date 2018/12/4 下午3:23
     * @param jobId job id
     * @return add result
     */
	public static boolean addJobEvent(Long jobId) {
        JobInfo jobInfo = jobInfoMapper.findJobInfoById(jobId);
        return jobInfo != null && addJobEvent(jobInfo);
    }

}
