/*
 * Copyright (c) 2011 by the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.powertac.common.msg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.StringWriter;
import java.util.List;

import org.joda.time.Instant;
import org.junit.Before;
import org.junit.Test;
import org.powertac.common.Competition;
import org.powertac.common.TimeService;
import org.powertac.common.Timeslot;
import org.powertac.common.repo.TimeslotRepo;
import org.springframework.test.util.ReflectionTestUtils;

import com.thoughtworks.xstream.XStream;

/**
 * Tests for the TimeslotUpdate message type.
 * @author John Collins
 */
public class TimeslotUpdateTests
{
  TimeService timeService;
  TimeslotRepo timeslotRepo;
  
  @Before
  public void setUp () throws Exception
  {
    Competition.setCurrent(Competition.newInstance("test"));
    timeService = new TimeService();
    Instant start = Competition.currentCompetition().getSimulationBaseTime();
    timeService.setCurrentTime(start);
    timeslotRepo = new TimeslotRepo();
    ReflectionTestUtils.setField(timeslotRepo, "timeService", timeService);
    for (int i = 0; i < 15; i++) {
      timeslotRepo.makeTimeslot(start.plus(TimeService.HOUR * i));
    }

  }

  @Test
  public void testTimeslotUpdate ()
  {
    List<Timeslot> enabled = timeslotRepo.enabledTimeslots();
    TimeslotUpdate tsu = new TimeslotUpdate(timeService.getCurrentTime(),
                                            enabled.get(0).getSerialNumber(),
                                            enabled.get(enabled.size() - 1).getSerialNumber());
    assertNotNull("message not null", tsu);
    assertEquals("24 timeslots", 24, tsu.size());
    assertEquals("correct posted time",
                 timeService.getCurrentTime(),
                 tsu.getPostedTime());
    int first = tsu.getFirstEnabled();
    int last = tsu.getLastEnabled();
    assertEquals("24 elements in list", 23, last - first);
    assertEquals("first sn is 1", 1, first);
    assertEquals("correct first element",
                 timeslotRepo.enabledTimeslots().get(0).getSerialNumber(),
                 first);
    assertEquals("correct last element",
                 timeslotRepo.enabledTimeslots().get(23).getSerialNumber(),
                 last);
  }

  @Test
  public void xmlSerializationTest ()
  {
    List<Timeslot> enabled = timeslotRepo.enabledTimeslots();
    TimeslotUpdate tsu = new TimeslotUpdate(timeService.getCurrentTime(),
                                            enabled.get(0).getSerialNumber(),
                                            enabled.get(enabled.size() - 1).getSerialNumber());
    XStream xstream = new XStream();
    xstream.processAnnotations(TimeslotUpdate.class);
    StringWriter serialized = new StringWriter();
    serialized.write(xstream.toXML(tsu));
    //System.out.println(serialized.toString());
    TimeslotUpdate xtsu= (TimeslotUpdate)xstream.fromXML(serialized.toString());
    assertNotNull("deserialized something", xtsu);
    assertEquals("correct time",
                 timeService.getCurrentTime(),
                 xtsu.getPostedTime());
    assertEquals("correct first", enabled.get(0).getSerialNumber(), xtsu.getFirstEnabled());
    assertEquals("correct last", enabled.get(23).getSerialNumber(), xtsu.getLastEnabled());
    assertEquals("correct length", 24, xtsu.size());
  }
}
