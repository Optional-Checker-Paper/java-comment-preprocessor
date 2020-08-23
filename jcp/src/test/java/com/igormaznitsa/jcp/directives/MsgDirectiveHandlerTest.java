/*
 * Copyright 2002-2019 Igor Maznitsa (http://www.igormaznitsa.com)
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.igormaznitsa.jcp.directives;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;


import com.igormaznitsa.jcp.logger.PreprocessorLogger;
import java.util.List;
import org.mockito.ArgumentCaptor;

public class MsgDirectiveHandlerTest extends AbstractDirectiveHandlerAcceptanceTest {

  private static final MsgDirectiveHandler HANDLER = new MsgDirectiveHandler();

  @Override
  public void testExecution() throws Exception {
    final PreprocessorLogger mock = mock(PreprocessorLogger.class);
    assertFilePreprocessing("directive_msg.txt", false, null, mock);

    ArgumentCaptor<String> varArgs = ArgumentCaptor.forClass(String.class);
    verify(mock, times(2)).info(varArgs.capture());
    final List<String> calls = varArgs.getAllValues();
    assertEquals(2, calls.size());
    assertEquals("  string 2 ok  ", calls.get(0));
    assertEquals("string 48 ok", calls.get(1));
  }

  @Override
  public void testKeyword() throws Exception {
    assertEquals("msg", HANDLER.getName());
  }

  @Override
  public void testExecutionCondition() throws Exception {
    assertTrue(HANDLER.executeOnlyWhenExecutionAllowed());
  }

  @Override
  public void testReference() throws Exception {
    assertReference(HANDLER);
  }

  @Override
  public void testArgumentType() throws Exception {
    assertEquals(DirectiveArgumentType.TAIL, HANDLER.getArgumentType());
  }

  @Override
  public void testPhase() throws Exception {
    assertTrue(HANDLER.isPreprocessingPhaseAllowed());
    assertFalse(HANDLER.isGlobalPhaseAllowed());
  }
}
