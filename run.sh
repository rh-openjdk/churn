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

function ljava() {
  if [ "x$JAVA_HOME" != "x" ]; then
      LJAVA=${JAVA_HOME}/bin/java
      LJAR=${JAVA_HOME}/bin/jar
      LJAVAC=${JAVA_HOME}/bin/javac
      echo "use java from JAVA_HOME [${LJAVA}]"
      echo "use javac from JAVA_HOME [${LJAVAC}]"
  elif [ $( which java ) ]; then
      LJAVA=$( which java )
      LJAR=$( which jar )
      LJAVAC=$( which javac )
      echo "use java from PATH [${LJAVA}]"
      echo "use java from PATH [${LJAVAC}]"
  else
      echo "no java found!"
      LJAVA=java
      LJAR=jar
      LJAVAC=javac
  fi
}

function globalInfo() {
  uname -a > outlog-global
  ljava
  ${LJAVA} -version 2>>outlog-global || true
  echo "NOCOMP=${NOCOMP}">>outlog-global
  echo "GC=${GC}">>outlog-global
  echo "OTOOL_garbageCollector=${OTOOL_garbageCollector}">>outlog-global
  echo "OTOOL_JDK_VERSION=${OTOOL_JDK_VERSION}">>outlog-global
}

function junitResults() {
(
  wget https://raw.githubusercontent.com/rh-openjdk/run-folder-as-tests/main/jtreg-shell-xml.sh;
  jtrXml=`pwd`/jtreg-shell-xml.sh
  if [ -e $jtrXml ] ; then
    source  $jtrXml
    total=`echo $results | wc -w `
    pass=`echo "$results" | grep -e =0 | wc -l`
    fail=`echo "$results" | grep -e =1 | wc -l`
    printXmlHeader $pass $fail $total 0 churn${NOCOMP} `hostname` > churn${NOCOMP}.jtr.xml
	  for result in $results ;  do
      name=`echo $result | sed "s/=.*//"`
      if echo $result | grep -e "=0" ; then
        printXmlTest churn $name $DURATION >> churn${NOCOMP}.jtr.xml
      else
        fileName=`ls outlog-$name-*`
        printXmlTest churn $name $DURATION $fileName "$fileName and gclog-$name-* in gclogs${NOCOMP}${STAMP}.tar.gz" >> churn${NOCOMP}.jtr.xml
      fi
    done
    printXmlFooter >> churn${NOCOMP}.jtr.xml
    rm -v $jtrXml
    set -e
    tar -cvzf churn${NOCOMP}${STAMP}.jtr.xml.tar.gz churn${NOCOMP}.jtr.xml
    rm churn${NOCOMP}.jtr.xml
  fi
) || true
}


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
	if [ "x$OTOOL_JDK_VERSION" == "x" ] ; then
      GC="shenandoah zgc cms par g1" ## unset, main set set
    elif [ "0$OTOOL_JDK_VERSION" -le 7  ] ; then
      GC="               cms  par g1" ## we claim adoptium jdk8 as 7, as it do not have shenandoah.
  	elif [ "0$OTOOL_JDK_VERSION" -ge 8  -a "0$OTOOL_JDK_VERSION" -le 11 ] ; then
      GC="shenandoah     cms  par g1" # zgc arrived in jdk11
	elif [ "0$OTOOL_JDK_VERSION" -gt 11  -a "0$OTOOL_JDK_VERSION" -le 20 ] ; then
      GC="shenandoah zgc          g1" # no more cms or par
    else
     # in jdk 21 arrived generational zgc
     GC="shenandoah zgc           g1 zgcgen" # zgcgem arrived in jdk21
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
  echo 'expected exactly one command line argument - garbage collector [g1|cms|par|shenandoah] or use OTOOL_garbageCollector variabnle with same values + two more - "defaultgc" and "ALL", wich will cause this to use default gc or iterate through all GCs (time will be divided). You should accompany it by OTOOL_JDK_VERSION=<8..21> so the proper set of jdks is chosen. Use NOCOMP=-nocoops to disable compressed oops.' >&2  
  exit 1
fi

if [ ! "x$STAMP"  == "x" ] ; then
  ljava
  arch=`uname -m | sed "s/[^a-zA-Z0-9_]//g"`
  os=`uname -o | sed "s/[^a-zA-Z0-9_]//g"`
  jdk=`${LJAVA} -version 2>&1 | head -n1 | sed -E "s/[^0-9]+/-/g"`
  time=`date +%s`
  STAMP="-${os}_${arch}_jdk${jdk}_${time}"
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
  if which mvn ; then
    if [ "x$MVOPTS"  == "x" ] ; then
      MVOPTS="--batch-mode"
    fi
    pushd $CH_SCRIPT_DIR
      mvn $MVOPTS install
    popd
  else
    ljava
    pushd $CH_SCRIPT_DIR/src/main/java/
      ${LJAVAC} -d $CH_SCRIPT_DIR/target/classes `find . -type f | grep ".java$"`
      pushd $CH_SCRIPT_DIR/target/classes
        ${LJAR} -cf $CH_SCRIPT_DIR/target/churn-1.0.jar *
      popd
    popd
  fi
fi

results=""
pushd ${CH_SCRIPT_DIR}
  globalInfo
  TEST_RESULT=0
  echo $GC
  for gc in $GC; do
     echo "*** $gc ***"
    one_result=0
	HEAPSIZE=${HEAPSIZE} bash -ex bin/run${gc}${NOCOMP}.sh -items ${ITEMS} -threads ${THREADS} -duration ${DURATION} -blocks ${BLOCKS} -computations ${COMPUTATIONS} || one_result=1
    let TEST_RESULT=$TEST_RESULT+$one_result || true
    results="$results
$gc=$one_result"
  done

  #the test results (gclog*) wont be there if it fails for some reason
  gclogsCount=`ls gclog-* | wc -l`
  if [ 0$gclogsCount -gt  0 ] ; then
    tar -cvzf gclogs${NOCOMP}${STAMP}.tar.gz outlog-* gclog-*
  else
    tar -cvzf gclogs${NOCOMP}${STAMP}.tar.gz outlog-*
  fi
popd

#optionally generate juit result file
junitResults

#the logs are already packed
if [ 0$gclogsCount -gt  0 ] ; then
  rm -v ${CH_SCRIPT_DIR}/outlog* ${CH_SCRIPT_DIR}/gclog-* 
else  
  rm -v ${CH_SCRIPT_DIR}/outlog-*
fi
if [ ! `readlink -f ${CH_SCRIPT_DIR}` == `pwd`  ] ; then
  if [ 0$gclogsCount -gt  0 ] ; then
    mv -v ${CH_SCRIPT_DIR}/gclogs${NOCOMP}${STAMP}.tar.gz .
  fi
fi

echo "$results"
exit $TEST_RESULT
