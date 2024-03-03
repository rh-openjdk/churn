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

function getjava() {
  if [ "x$JAVA_HOME" != "x" ]; then
      LJAVA=${JAVA_HOME}/bin/java
      LJAR=${JAVA_HOME}/bin/jar
      LJAVAC=${JAVA_HOME}/bin/javac
      echo "use java from JAVA_HOME [${LJAVA}]"
      echo "use javac from JAVA_HOME [${LJAVAC}]"
  elif [ $( which java ) ]; then
      LJAVA=$( readlink -f $(which   java) )
      LJAVAC=$( readlink -f $(which   javac) )
      LJAR="$(dirname ${LJAVAC})/jar"
      echo "use java from PATH [${LJAVA}]"
      echo "use java from PATH [${LJAVAC}]"
  else
      echo "no java found!"
      LJAVA=java
      LJAR=jar
      LJAVAC=javac
  fi
}

function depath() {
  echo "${1}" | depathStream
}

function depathStream() {
  platform="$(uname)"
  while IFS= read -r line ; do
    if [ "${platform#"CYGWIN_NT"}" != "$platform" ]; then
      cygpath -w "${line}"
    else
      echo "${line}"
    fi
  done
}

function checkXX() {
   ${LJAVA} -XX:+PrintFlagsFinal -version 2>/dev/null | grep -e ${1}
}

function globalInfo() {
  uname -a > outlog-global
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
    printXmlHeader $pass $fail $total 0 churn${NOCOMP} `hostname` > ${resultsXmlFile}
	  for result in $results ;  do
        name=`echo $result | sed "s/=.*//"`
        if echo $result | grep -e "=0" ; then
          printXmlTest churn $name $DURATION >> ${resultsXmlFile}
        else
          fileName1=`ls outlog-$name-*`
          fileName2=`ls gclog-$name-*`
          printXmlTest churn $name $DURATION $fileName1 "$fileName1, outlog-$name-*, gclog-$name-* in gclogs${NOCOMP}${STAMP}.tar.gz" >> ${resultsXmlFile}
        fi
      done
    printXmlFooter >> ${resultsXmlFile}
    rm -v $jtrXml
    set -e
    tar -cvzf ${jtrTarball} ${resultsXmlFile}
    rm ${resultsXmlFile}
  fi
) || true
}

function tapResults() {
(
  wget https://raw.githubusercontent.com/rh-openjdk/run-folder-as-tests/main/tap-shell-tap.sh;
  taptap=`pwd`/tap-shell-tap.sh
  if [ -e $taptap ] ; then
    source  $taptap
    total=`echo $results | wc -w `
    tapHeader "$total"  "`date`" > ${resultsTapFile}
      counter=0;
	  for result in $results ;  do
        let counter=$counter+1;
        name=`echo $result | sed "s/=.*//"`
        fileName1=`ls outlog-$name-*`
        fileName2=`ls gclog-$name-*`
        if echo $result | grep -e "=0" ; then
          tapTestStart "ok" "$counter" "$name" >> ${resultsTapFile}
        else
          tapTestStart "not ok" "$counter" "$name" >> ${resultsTapFile}
        fi
        tapLine "info" "churn $name duration of ${DURATION}s see $fileName1, outlog-$name-*, gclog-$name-* in gclogs${NOCOMP}${STAMP}.tar.gz" >> ${resultsTapFile}
        tapLine "duration_ms" "${DURATION}000" >> ${resultsTapFile}
        tapFromFile "$fileName1" "outlog-$name-*">> ${resultsTapFile}
        # gclog now disabled - sometimes to big, sometimes several of them (easy to merge, but...), sometimes with buggy content
        #tapFromFile "$fileName2" "gclog-$name-*">> ${resultsTapFile}
        tapTestEnd "$fileName">> ${resultsTapFile}
      done
  rm -v $taptap
  fi
) || true
}

getjava

GC=${1}
if [ "x$GC" == "x" ] ; then
  #todo add generational zgc since jdk21, todo add generational shenandoah sicnce  jdk23?
  if [ "x$OTOOL_garbageCollector" == "xshentraver" ] ; then
    GC=shenandoah
  elif [ "x$OTOOL_garbageCollector" == "xALL" ] ; then
    GC=""
    if checkXX UseShenandoahGC ; then 
      GC="$GC shenandoah"
    fi
    if checkXX UseZGC ; then 
      GC="$GC zgc"
      if checkXX ZGenerational ; then 
        GC="$GC zgcgen"
      fi
    fi
    if checkXX UseConcMarkSweepGC ; then 
      GC="$GC cms"
    fi
    if checkXX UseParallelGC ; then 
      GC="$GC par"
      if checkXX UseParallelOldGC ; then 
        GC="$GC parold"
      fi
    fi
    if checkXX UseG1GC ; then 
      GC="$GC g1"
    fi
  elif [ "x$OTOOL_garbageCollector" == "xdefaultgc" ] ; then
    if [ "0$OTOOL_JDK_VERSION" -le 8 ] ; then
      GC=par
      echo "double checking default gc is correct:"
      checkXX UseParallelGC | grep  true
    else
      GC=g1
      echo "double checking default gc is correct:"
      checkXX UseG1GC | grep  true
    fi
  else
    GC="$OTOOL_garbageCollector"
  fi
fi

echo "GC=$GC"  >&2

if [ "x$GC" == "x" ] ; then
  echo 'expected exactly one command line argument - garbage collector [g1|cms|par|shenandoah] or use OTOOL_garbageCollector variabnle with same values + two more - "defaultgc" and "ALL", wich will cause this to use default gc or iterate through all GCs (time will be divided). You should accompany it by OTOOL_JDK_VERSION=<8..21> so the proper set of jdks is chosen. Use NOCOMP=-nocoops to disable compressed oops.' >&2  
  exit 1
fi

if [ ! "x$STAMP"  == "x" ] ; then
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
    pushd $CH_SCRIPT_DIR/src/main/java/
      ${LJAVAC} -d $(depath $CH_SCRIPT_DIR/target/classes) `find . -type f | grep ".java$" | depathStream`
      pushd $CH_SCRIPT_DIR/target/classes
        ${LJAR} -cf $(depath $CH_SCRIPT_DIR/target/churn-1.0.jar) *
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

  #optionally generate juit and tap results files
  resultsXmlFile=churn${NOCOMP}.jtr.xml
  jtrTarball=churn${NOCOMP}${STAMP}.jtr.xml.tar.gz
  resultsTapFile=churn${NOCOMP}${STAMP}.tap
  set +x
    if [ "x$CHURN_JUNIT" == "x" -o "x$CHURN_JUNIT" == "xtrue" ] ; then
      junitResults
    fi
    if [ "x$CHURN_TAP" == "x" -o "x$CHURN_TAP" == "xtrue" ] ; then
        tapResults
    fi
  set -x
popd

#the logs are already packed
if [ 0$gclogsCount -gt  0 ] ; then
  rm -v ${CH_SCRIPT_DIR}/outlog* ${CH_SCRIPT_DIR}/gclog-* 
else  
  rm -v ${CH_SCRIPT_DIR}/outlog-*
fi
if [ ! `readlink -f ${CH_SCRIPT_DIR}` == `pwd`  ] ; then
    mv -v ${CH_SCRIPT_DIR}/gclogs${NOCOMP}${STAMP}.tar.gz .
    if [ -e ${CH_SCRIPT_DIR}/$jtrTarball ] ; then
      mv -v ${CH_SCRIPT_DIR}/$jtrTarball .
    fi
    if [ -e ${CH_SCRIPT_DIR}/$resultsTapFile ] ; then
      mv -v ${CH_SCRIPT_DIR}/$resultsTapFile .
    fi
fi

echo "$results"
exit $TEST_RESULT
