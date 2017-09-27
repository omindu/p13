/*
*  Copyright (c) 2005-2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
*  WSO2 Inc. licenses this file to you under the Apache License,
*  Version 2.0 (the "License"); you may not use this file except
*  in compliance with the License.
*  You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied.  See the License for the
* specific language governing permissions and limitations
* under the License.
*/
package org.wso2.carbon.user.core.util;

import edu.emory.mathcs.backport.java.util.Arrays;
import junit.framework.Assert;
import org.wso2.carbon.user.core.BaseTestCase;

import java.util.ArrayList;
import java.util.List;

public class UserCoreUtilTest extends BaseTestCase {
    public void setUp() throws Exception {
        super.setUp();
    }

    public void testCombine() throws Exception {
        String[] array = {"a", "c", "b"};

        List<String> list = new ArrayList<String>();
        list.add("d");
        list.add("c");
        list.add("e");

        String[] combined = UserCoreUtil.combine(array, list);
        Arrays.sort(combined);

        String[] expected = {"a", "b", "c", "d", "e"};

        Assert.assertTrue(Arrays.equals(combined, expected));
    }
}
