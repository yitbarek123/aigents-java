/*
 * MIT License
 * 
 * Copyright (c) 2005-2020 by Anton Kolonin, Aigents®
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package net.webstructor.self;

import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;

import net.webstructor.agent.Body;
import net.webstructor.agent.Farm;
import net.webstructor.agent.Schema;
import net.webstructor.al.AL;
import net.webstructor.al.Period;
import net.webstructor.al.Reader;
import net.webstructor.al.Time;
import net.webstructor.core.Thing;
import net.webstructor.data.GraphCacher;
import net.webstructor.peer.Peer;
import net.webstructor.util.Array;

import java.util.regex.Pattern;

class SyncLong {
	private long value;
	public SyncLong(long value) {
		this.value = value;
	}
	public synchronized long get() { return value; } 
	public synchronized void set(long value) { this.value = value; } 
}

public class Selfer extends Thread {
	private static final long DEFAULT_STORE_CYCLE_MS = Period.MINUTE;
	private static final long DEFAULT_FORGET_CYCLE_MS = Period.DAY / 4;
	public final static long MIN_CHECK_CYCLE_MS = 2 * Period.HOUR;

	Body body;
	Spider spider;
	
	private long store_cycle = DEFAULT_STORE_CYCLE_MS;
	private long last_store_time = 0;
	private long next_store_time = 0;

	private long forget_cycle = DEFAULT_FORGET_CYCLE_MS;
	private long next_forget_time = 0;
	
	private SyncLong next_profile_time = new SyncLong(0);
	private SyncLong next_spider_time = new SyncLong(0);
		
	public Selfer(Body body) {
		this.body = body;

		//TODO: ensure clear storager and no schema conflict
		String path = body.self().getString(Farm.store_path);
		if (!AL.empty(path)) {
			Self.load(body,path);
			
			body.self().setString(Body.store_path,path);
			body.reply("Loaded "+path+" times "+new Date(System.currentTimeMillis()).toString()+".");
		}
		
		upgrade(Body.VERSION);
		
		last_store_time = System.currentTimeMillis();
		
		//TODO: be able to update this on the fly		
		store_cycle = new Period(store_cycle).parse(body.self().getString(Farm.store_cycle));

		spider = new Spider(body);
	}

    public static String normalisedVersion(String version) {
        return normalisedVersion(version, ".", 4);
    }

    public static String normalisedVersion(String version, String sep, int maxWidth) {
        String[] split = Pattern.compile(sep, Pattern.LITERAL).split(version);
        StringBuilder sb = new StringBuilder();
        if (AL.empty(split))
        	return "";
        for (int i = 0; i < split.length; i++) {
        	String s = split[i];
            sb.append(String.format("%" + maxWidth + 's', (Object[])new String[]{s}));
        }
        return sb.toString();
    }	
    
    //TODO: move to Upgrader class
	//check version and upgrade, if needed
	void upgrade(String version) {
		String current_version = body.self().getString(AL.version,"");
		if (normalisedVersion(current_version).compareTo(normalisedVersion("1.2.13")) < 0){
			//make all peers in trusts to be also in friends, so any peer friends may be either in trusts or in shares or in both or in none 
			try {
				Collection peers = body.storager.getByName(AL.is, Peer.peer);
				if (!AL.empty(peers)) for (Iterator it = peers.iterator(); it.hasNext();){
					Thing peer = (Thing)it.next();
					Collection trusts = peer.getThings(AL.trusts);
					if (!AL.empty(trusts)){
						Collection trusted = new HashSet(trusts);				
						trusted.retainAll(peers);
						if (!AL.empty(trusted)) for (Iterator jt = trusted.iterator(); jt.hasNext();)
							peer.addThing(AL.friends,(Thing)jt.next());
					}
				}
			} catch (Exception e) {
				body.error("Upgrade error "+current_version+" to 1.2.13", e);
			}
		}
		if (normalisedVersion(current_version).compareTo(normalisedVersion("1.6.8")) < 0){
			try {//rename all knows to topics
				Collection things = body.storager.getThings();
				for (Iterator it = things.iterator(); it.hasNext();) {
					Thing t = (Thing)it.next();
					String knows = "knows";
					for (;;){//loop with self-modification of iterator
						Collection ks = t.getThings(knows);
						if (AL.empty(ks))
							break;
						Thing k = (Thing)ks.iterator().next();
						t.delThing(knows, k);
						t.addThing(AL.topics, k);
					}
				}
			} catch (Exception e) {
				body.error("Upgrade error "+current_version+" to 1.6.8", e);
			}
		}
//TODO upgrade
		if (normalisedVersion(current_version).compareTo(normalisedVersion("2.8.3")) < 0){
			try {//rename all knows to topics
				Collection things = body.storager.getThings();
				for (Iterator it = things.iterator(); it.hasNext();) {
					Thing t = (Thing)it.next();
					String path = t.getString(AL.path);
					if (AL.empty(path))
						continue;
					String compacted = Reader.patterns(body.storager,null,path).compact().toString();
					if (!path.equals(compacted) ) {
//TODO: cleanup
//System.out.println(path);						
//System.out.println(compacted);						
						t.setString(AL.path, compacted);
					}
				}
			} catch (Exception e) {
				body.error("Upgrade error "+current_version+" to 2.8.3", e);
			}
		}
		body.debug("Upgrade completed to "+Body.VERSION);
		body.self().setString(AL.version,Body.VERSION);
	}
	
	public void forget(long start_time){
		if (start_time >= next_forget_time) {	
			body.debug("Selfer forgetting start "+new Date(start_time)+", memory "+body.checkMemory()+".");

			Self.clear(body,Schema.foundation);
			
//TODO make MEMORY_THRESHOLD parameter of body/self
//TODO built-into cacheholder?
			int memory = body.checkMemory();
			if (body.checkMemory() > GraphCacher.MEMORY_THRESHOLD) {
				body.cacheholder.free();
				body.debug("Selfer free, memory "+memory+" to "+body.checkMemory());
			}
			
			long end_time = System.currentTimeMillis();
			body.debug("Selfer forgetting stop "+new Date(end_time)+", memory "+body.checkMemory()+", took "+new Period(end_time-start_time).toHours()+".");
			next_forget_time = start_time + forget_cycle;
		}
	}
	
	public void store(long start_time){
		if (start_time >= next_store_time) {
			if (next_store_time != 0){
				body.debug("Saving start "+new Date(start_time)+".");
				save(start_time);
				long end_time = System.currentTimeMillis();
				body.debug("Saving end "+new Date(end_time)+", took "+new Period(end_time-start_time).toHours()+".");
			}
			next_store_time = start_time + store_cycle;
		}
	}
	
	public void run() 
	{
		for (;;) {
			try {
				boolean update = false;
				long current_time = System.currentTimeMillis();
				
				//!!! do forgetting immediately on startup
				forget(current_time);
				
				current_time = System.currentTimeMillis();
				//if current time >= next reading time
					//get all user sites and things
					//do reading for all user sites and things //TODO:may optimize skipping some sites for some users
					//TODO: how to update the site
					//send notifications to users (accordingly to their check cycle settings)
					//get min user check cycle (along the way with above?)
					//next reading time += min user check cycle				
				if (current_time >= next_spider_time.get()) {
					long cycle = minCheckCycle();
					long next_time = current_time + cycle;
					//TODO: need to check current tasks to preven over-spidering?
					if (next_spider_time.get() != 0  /*&& spider.tasks() == 0*/) {
						//TODO: this all in separate thread, not re-enterable till completion!?
						try {
		    	    		long start_time = System.currentTimeMillis();
							body.debug("Sites crawling start "+new Date(start_time)+", next time "+new Date(next_time)+".");
							spider.spider(next_time);
		    	    		long end_time = System.currentTimeMillis();
							body.debug("Sites crawling stop  "+new Date(end_time)+", took "+new Period(end_time-start_time).toHours()+".");
						} catch (Exception e) {
							body.error("Sites crawling error :"+e.toString(),e);
						}
					}
					next_spider_time.set( next_time );
					update = true;
				}

				//just in case, cleanup memory and auto-store in-between site spidering and peer profiling 
				current_time = System.currentTimeMillis();
				forget(current_time);
				current_time = System.currentTimeMillis();
				store(current_time);
				
				//social profiling - once per day? 
				current_time = System.currentTimeMillis();
				if (current_time >= next_profile_time.get()){
					if (next_profile_time.get() == 0)
						next_profile_time.set( current_time + Period.HOUR );//delay profiling after restart for 1 hour
					else {
						try {
							//TODO: this is separate "socializer" class
							long start_time = current_time;
							body.debug("Social crawling start "+new Date(start_time)+".");
							body.updateStatusRarely();
							long end_time = System.currentTimeMillis();
							body.debug("Social crawling stop "+new Date(end_time)+", took "+new Period(end_time-start_time).toHours()+".");
						} catch (Throwable e) {
							body.error("Social crawling error "+e.toString(),e);
						}
						next_profile_time.set( current_time + Period.HOUR * 24 );//TODO: make configurable
					}
					update = true;
				}
				
				//auto-store at the end
				current_time = System.currentTimeMillis();
				store(current_time);
				
				body.updateStatus(update);//this cleans up memory immediately after spidering cycle
				current_time = System.currentTimeMillis();
				long next_time = Array.min(new long[]{next_forget_time,next_store_time,next_profile_time.get(),next_spider_time.get()});
				if (next_time > current_time) {
					synchronized (this) {
						this.wait(next_time - current_time);
					}
				}

			} catch (Exception e) {
				body.error("Selfer error (" + e.toString() + ")",e);
			}
		}//while
	}

	//schedule next spidering as soon as possible
	public boolean spider(Thing target) {
		//unschedulded spidering
		//TODO: make scheduled via spidering thread pool?
		if (target != null && !AL.empty(target.getString("url"))){
			//read "forced" by peer
			String date = target.getString(AL.time);
			Date time = date == null ? new Date() : Time.date(date);
			String scope =target.getString("scope", "site");
			int searchRange = Integer.parseInt(target.getString("range", "3"), 10);//default range 3
			int minutes = Integer.parseInt(target.getString("minutes", "1"), 10);
			int limit = Integer.parseInt(target.getString("limit", "0"), 10);
			long tillTime = minutes <= 0 ? 0 : System.currentTimeMillis() + Period.MINUTE * minutes;
			body.filecacher.clear(false,time);//reset cache to current point in time
			boolean ok = spider.spider(target.getString("url"), target.getString("thingname"), time, tillTime, true, searchRange, limit, "site".equalsIgnoreCase(scope) ? true : false, target.getString("mode"));
			if (ok && body.sitecacher != null)
				body.sitecacher.updateGraph(time, body.sitecacher.getGraph(Time.date(time)), System.currentTimeMillis());
			return ok;
		}

		synchronized (this) {
			if (next_spider_time.get() == 1)//already busy
				return false;
			next_spider_time.set(1);
			this.notify();
		}
		return true;
	}

	public boolean profile() {
		synchronized (this) {
			if (next_profile_time.get() == 1)//already busy
				return false;
			next_profile_time.set(1);
			this.notify();
		}
		return true;
	}
	
	//get min user check cycle
	public long minCheckCycle() {
		long cycle = 0;
		String[] cycles = body.storager.getNames(Peer.check_cycle);
		if (!AL.empty(cycles))
			for (int i = 0; i < cycles.length; i++) {
				long check_cycle = Period.parseUnits(cycles[i],Period.HOUR);
				if (cycle == 0 || cycle > check_cycle)
					cycle = check_cycle;
			}
		//enforce absolute minimum
		long min = new Period(MIN_CHECK_CYCLE_MS).parse(body.self().getString(Peer.check_cycle,String.valueOf(MIN_CHECK_CYCLE_MS / Period.HOUR)));
		if (min < MIN_CHECK_CYCLE_MS)
			min = MIN_CHECK_CYCLE_MS;
		if (cycle < min)
			cycle = min;
		return cycle;
	}

	public void save(long time) 
	{
		long update_time = body.storager.getUpdate();
		if (update_time > last_store_time) {
			body.reply("Saving times "+new Date(time).toString()+".");
			last_store_time = time;
			String path = body.self().getString(Farm.store_path);
			if (!AL.empty(path))
				Self.save(body,path);
		}
	}
	
}
