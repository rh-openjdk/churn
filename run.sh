#!/bin/bash
SCRIPT_SOURCE="${BASH_SOURCE[0]}"
while [ -h "$SCRIPT_SOURCE" ]; do # resolve $SOURCE until the file is no longer a symlink
  CH_SCRIPT_DIR="$( cd -P "$( dirname "$SCRIPT_SOURCE" )" && pwd )"
  SCRIPT_SOURCE="$(readlink "$SCRIPT_SOURCE")"
  # if $SOURCE was a relative symlink, we need to resolve it relative to the path where the symlink file was located
  [[ $SCRIPT_SOURCE != /* ]] && SCRIPT_SOURCE="$CH_SCRIPT_DIR/$SCRIPT_SOURCE"
done
readonly CH_SCRIPT_DIR="$( cd -P "$( dirname "$SCRIPT_SOURCE" )" && pwd )"
set -ex



GC=${1}
if [ "x$GC" == "x" ] ; then
  #todo add generational zgc since jdk21, todo add generational shenandoah sicnce  jdk23?
  #todo, remove shentraver iirc (not sure when)
  if [ "x$OTOOL_garbageCollector" == "xshenandoah" ] ; then
    GC=shenandoah
  elif [ "x$OTOOL_garbageCollector" == "xshentraver" ] ; then
    GC=shenandoah
  elif [ "x$OTOOL_garbageCollector" == "xzgc" ] ; then
    GC=zgc
  elif [ "x$OTOOL_garbageCollector" == "xcms" ] ; then
    GC=cms
  elif [ "x$OTOOL_garbageCollector" == "xpar" ] ; then
    GC=par
  elif [ "x$OTOOL_garbageCollector" == "xg1" ] ; then
    GC=g1
  elif [ "x$OTOOL_garbageCollector" == "xALL" ] ; then
	if [ "0$OTOOL_JDK_VERSION" -gt 8 -o "x$OTOOL_JDK_VERSION" == "x" ] ; then
      GC="shenandoah zgc cms par g1"
	else
      GC="shenandoah     cms  par g1"
    fi
  elif [ "x$OTOOL_garbageCollector" == "xdefaultgc" ] ; then
    if [ "0$OTOOL_JDK_VERSION" -le 8 ] ; then
      GC=par
    else
      GC=g1
    fi
  fi
fi

echo "GC=$GC"  >&2
if [ "x$GC" == "x" ] ; then
  echo 'expected exactly one command line argument - garbage collector [g1|cms|par|shenandoah] or use OTOOL_garbageCollector variabnle with same values + two more - "defaultgc" and "ALL", wich will cause this to use default gc or iterate through all GCs (time will be divided). Use NOCOMP=-nocoops to disable compressed oops.' >&2  
  exit 1
fi

# churn parameters sane defaults
if [ "x$HEAPSIZE"  == "x" ] ; then
  HEAPSIZE=3g
fi
if [ "x$ITEMS"  == "x" ] ; then
  ITEMS=250
fi
if [ "x$THREADS"  == "x" ] ; then
  THREADS=2
fi
if [ "x$DURATION"  == "x" ] ; then
  DURATION=18000 # 5 hours (in seconds)
fi
if [ "x$OTOOL_garbageCollector" == "xALL" ] ; then
  gcs=`echo "$GC" | wc -w`
  let "DURATION=$DURATION/$gcs"
  echo "all GCs will run. Time per one is: $DURATION"
fi

if [ "x$COMPUTATIONS"  == "x" ] ; then
  COMPUTATIONS=64
fi
if [ "x$BLOCKS"  == "x" ] ; then
  BLOCKS=16
fi
if [ ! -e ${CH_SCRIPT_DIR}/target ] ; then
  if [ "x$MVOPTS"  == "x" ] ; then
    MVOPTS="--batch-mode"
  fi
  pushd $CH_SCRIPT_DIR
    mvn $MVOPTS install
  popd
fi

results=""
pushd ${CH_SCRIPT_DIR}
  TEST_RESULT=0
  echo $GC
  for gc in $GC; do
     echo "*** $gc ***"
    one_result=0
	HEAPSIZE=${HEAPSIZE} sh  -x bin/run${gc}${NOCOMP}.sh -items ${ITEMS} -threads ${THREADS} -duration ${DURATION} -blocks ${BLOCKS} -computations ${COMPUTATIONS} || one_result=1
    let TEST_RESULT=$TEST_RESULT+$one_result || true
    results="$results
$gc=$one_result"
  done

  #the test results (gclog*) wont be there if it fails for some reason
  gclogsCount=`ls gclog* | wc -l`
  if [ 0$gclogsCount -gt  0 ] ; then
    tar -cvzf gclogs${NOCOMP}.tar.gz outlog-* gclog-*
  else
    tar -cvzf gclogs${NOCOMP}.tar.gz outlog-*
  fi
popd
if [ `readlink -f ${CH_SCRIPT_DIR}` == `pwd`  ] ; then
  if [ 0$gclogsCount -gt  0 ] ; then
    rm  ${CH_SCRIPT_DIR}/outlog* ${CH_SCRIPT_DIR}/gclog-* 
  else  
    rm  ${CH_SCRIPT_DIR}/outlog-*
  fi
	
else
  if [ 0$gclogsCount -gt  0 ] ; then
    mv  ${CH_SCRIPT_DIR}/gclogs.tar.gz ${CH_SCRIPT_DIR}/outlog* ${CH_SCRIPT_DIR}/gclog-*  .
  else	
    mv ${CH_SCRIPT_DIR}/gclogs.tar.gz ${CH_SCRIPT_DIR}/outlog-* .
  fi
fi
echo "$results"
exit $TEST_RESULT
