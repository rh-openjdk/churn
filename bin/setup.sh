#
# JBoss, Home of Professional Open Source
# Copyright 2011, Red Hat and individual contributors
# by the @authors tag. See the copyright.txt in the distribution for a
# full listing of individual contributors.
#
# This is free software; you can redistribute it and/or modify it
# under the terms of the GNU Lesser General Public License as
# published by the Free Software Foundation; either version 2.1 of
# the License, or (at your option) any later version.
#
# This software is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
# Lesser General Public License for more details.
#
# You should have received a copy of the GNU Lesser General Public
# License along with this software; if not, write to the Free
# Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
# 02110-1301 USA, or see the FSF site: http://www.fsf.org.
#
# @authors Andrew Dinn
#
# set up basic options needed for all runs
# combine this with other scripts to complete setup
# and then exec run.sh

# set up java
if [ "x$JAVA_HOME" != "x" ]; then
    export JAVA=${JAVA_HOME}/bin/java
    echo "use java from JAVA_HOME [${JAVA}]"
elif [ $( which java ) ]; then
    export JAVA=$( which java )
    echo "use java from PATH [${JAVA}]"
else
    echo "no java found!"
    exit 1
fi
#${JAVA} -version
#${JAVA} -XX:+PrintFlagsFinal
${JAVA} -version

# set up heap size, gc print opts and default gc log file
if [ -z "$HEAPSIZE" ]; then
    echo "setting heap size to 15g"
    HEAPSIZE=15g
else
    echo "heap size is $HEAPSIZE"
fi
export HEAP_OPTS="-Xms$HEAPSIZE -Xmx$HEAPSIZE"
export OUT_LOG_FILE="outlog"
export GC_LOG_FILE="gclog"

# detect java version <= 8 and >= 9 because of changes introduced by JEP 158 (http://openjdk.java.net/jeps/158)
# java <= 8 has just `java -version`, java >= 9 has also `java --version`
# final GC_LOG_FILE is not known at the moment. We're going to eval $GC_LOG_FILE later in run.sh script
JV=0
${JAVA} --version &> /dev/null || JV=$?
if [[ ${JV} != 0 ]]; then
    # jdk <= 8
    export GC_PRINT_OPTS="-XX:+PrintGCTimeStamps -XX:+PrintGCDetails -XX:+PrintGCApplicationStoppedTime -XX:+PrintGCApplicationConcurrentTime -verbose:gc"
    export LOG_OPTS='-Xloggc:$GC_LOG_FILE'
else
    # jdk >= 9
    export GC_PRINT_OPTS=
    export LOG_OPTS='-Xlog:gc=trace:${GC_LOG_FILE} -Xlog:gc=warning'
fi
