#!/bin/bash
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

if [ ! -z "${COOPS_OPTS}" ]; then
    GC_LOG_FILE=${GC_LOG_FILE}-nocoops
    OUT_LOG_FILE=${OUT_LOG_FILE}-nocoops
fi

while [ $# -gt 1 -a "${1#-*}" != "$1" ]
do
    case $1 in
	-threads)
	    ARGS="$ARGS $1 $2"
            shift
            GC_LOG_FILE=${GC_LOG_FILE}-t$1
            OUT_LOG_FILE=${OUT_LOG_FILE}-t$1
	    shift;;
	-items)
	    ARGS="$ARGS $1 $2"
	    shift;
	    GC_LOG_FILE=${GC_LOG_FILE}-i$1
	    OUT_LOG_FILE=${OUT_LOG_FILE}-i$1
	    shift;;
	-blocks)
	    ARGS="$ARGS $1 $2"
	    shift
	    GC_LOG_FILE=${GC_LOG_FILE}-b$1
	    OUT_LOG_FILE=${OUT_LOG_FILE}-b$1
	    shift;;
	-iterations)
	    ARGS="$ARGS $1 $2"
            shift
	    GC_LOG_FILE=${GC_LOG_FILE}-n$1
	    OUT_LOG_FILE=${OUT_LOG_FILE}-n$1
	    shift;;
    -duration)
	    ARGS="$ARGS $1 $2"
            shift
	    GC_LOG_FILE=${GC_LOG_FILE}-d$1
	    OUT_LOG_FILE=${OUT_LOG_FILE}-d$1
	    shift;;
	-computations)
	    ARGS="$ARGS $1 $2"
            shift
	    GC_LOG_FILE=${GC_LOG_FILE}-c$1
	    OUT_LOG_FILE=${OUT_LOG_FILE}-c$1
	    shift;;
	-slices)
	    ARGS="$ARGS $1 $2"
            shift
	    GC_LOG_FILE=${GC_LOG_FILE}-s$1
	    OUT_LOG_FILE=${OUT_LOG_FILE}-s$1
	    shift;;
	-yieldMSecs)
	    ARGS="$ARGS $1 $2"
            shift
	    GC_LOG_FILE=${GC_LOG_FILE}-y$1
	    OUT_LOG_FILE=${OUT_LOG_FILE}-y$1
	    shift;;
	*)
	    echo "invalid option $1"
	    exit 1;;
    esac
done

if [ $# -gt 0 ]; then
    echo "invalid arguments $*"
fi

set -o pipefail # without pipefail, the below command will always return zero!
exec ${JAVA} ${HEAP_OPTS} \
    ${COOPS_OPTS} \
    ${GC_PRINT_OPTS} \
    ${GC_SPECIFIC_OPTS} \
    $( eval echo ${LOG_OPTS} ) \
    -cp target/classes org.jboss.churn.TestRunner $ARGS 2>&1 | tee ${OUT_LOG_FILE}

