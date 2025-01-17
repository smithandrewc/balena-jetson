SUMMARY = "Create flash artifacts without flashing"

LICENSE = "Apache-2.0"
LIC_FILES_CHKSUM = "file://${BALENA_COREBASE}/COPYING.Apache-2.0;md5=89aea4e17d99a7cacdbeed46a0096b10"

DEPENDS = " \
    coreutils-native \
    virtual/bootloader \
    virtual/kernel \
    tegra-binaries \
    tegra-bootfiles \
    tegra186-flashtools-native \
    dtc-native \
"

inherit deploy perlnative python3native

SRC_URI_jetson-tx2 = " \
    file://resinOS-flash186.xml \
    file://partition_specification186.txt \
    file://boot0_t186.bindiff \
"
SRC_URI_jetson-tx2-4gb = " \
    file://resinOS-flash186-4gb.xml \
    file://partition_specification186-4gb.txt \
    file://boot0_t186-4gb.bindiff \
"

IMAGE_UBOOT ??= "u-boot"

# These device trees are not generated by the
# kernel build, this is why we need to specify
# them here instead of machine.conf.
KERNEL_DEVICETREE_orbitty-tx2 = "${DEPLOY_DIR_IMAGE}/tegra186-tx2-cti-ASG001-USB3.dtb"
KERNEL_DEVICETREE_n510-tx2 = "${DEPLOY_DIR_IMAGE}/tegra186-tx2-aetina-n510-p3489-0888-a00-00-base.dtb"
KERNEL_DEVICETREE_n310-tx2 = "${DEPLOY_DIR_IMAGE}/tegra186-tx2-aetina-n310-p3489-0888-a00-00-base.dtb"
KERNEL_DEVICETREE_spacely-tx2 = "${DEPLOY_DIR_IMAGE}/tegra186-tx2-cti-ASG006-IMX274-6CAM.dtb"
KERNEL_DEVICETREE_blackboard-tx2 = "${DEPLOY_DIR_IMAGE}/tegra186-tx2-blackboard.dtb"
KERNEL_DEVICETREE_astro-tx2 = "${DEPLOY_DIR_IMAGE}/tegra186-tx2-cti-ASG001-revG+.dtb"

LNXFILE="u-boot.bin"
IMAGE_TEGRAFLASH_KERNEL="${DEPLOY_DIR_IMAGE}/${LNXFILE}"
DTBFILE ?= "${@os.path.basename(d.getVar('KERNEL_DEVICETREE', True).split()[0])}"

LDK_DIR = "${TMPDIR}/work-shared/L4T-${SOC_FAMILY}-${PV}-${PR}/Linux_for_Tegra"
B = "${WORKDIR}/build"
S = "${WORKDIR}"

BINARY_INSTALL_PATH = "/opt/tegra-binaries"

BOOTFILES=" \
    adsp-fw.bin \
    bmp.blob \
    bpmp.bin \
    camera-rtcpu-sce.img \
    dram-ecc.bin \
    eks.img \
    mb1_prod.bin \
    mb1_recovery_prod.bin \
    mce_mts_d15_prod_cr.bin \
    nvtboot_cpu.bin \
    nvtboot_recovery.bin \
    nvtboot_recovery_cpu.bin \
    preboot_d15_prod_cr.bin \
    slot_metadata.bin \
    spe.bin \
    nvtboot.bin \
    warmboot.bin \
    minimal_scr.cfg \
    mobile_scr.cfg \
    emmc.cfg \
"

do_configure_jetson-tx2() {
    local f

    sed -i -e "s/\[DTB_NAME\]/$(echo ${DTBFILE} | cut -d '.' -f 1)/g" ${WORKDIR}/partition_specification186.txt
    sed -i -e "s/DTB_NAME/${DTBFILE}/g" ${WORKDIR}/resinOS-flash186.xml
    if [ -d ${DEPLOY_DIR_IMAGE}/bootfiles ]; then
         rm -rf ${DEPLOY_DIR_IMAGE}/bootfiles
    fi;

    if [ -d ${DEPLOY_DIR_IMAGE}/tegra-binaries ]; then
         rm -rf ${DEPLOY_DIR_IMAGE}/tegra-binaries
    fi;

    mkdir -p ${DEPLOY_DIR_IMAGE}/bootfiles
    local f
    PATH="${STAGING_BINDIR_NATIVE}/tegra186-flash:${PATH}"
    rm -rf "${WORKDIR}/tegraflash"
    mkdir -p "${WORKDIR}/tegraflash"
    oldwd=`pwd`
    cd "${WORKDIR}/tegraflash"
    ln -s "${STAGING_DATADIR}/tegraflash/${MACHINE}.cfg" .
    ln -s "${IMAGE_TEGRAFLASH_KERNEL}" ./${LNXFILE}
    cp "${DEPLOY_DIR_IMAGE}/${DTBFILE}" ./${DTBFILE}

    if [ "${BL_IS_CBOOT}" = "1" -a -n "${KERNEL_ARGS}" ]; then
        cp "${DEPLOY_DIR_IMAGE}/${DTBFILE}" ./${DTBFILE}
        bootargs="`fdtget ./${DTBFILE} /chosen bootargs 2>/dev/null`"
        fdtput -t s ./${DTBFILE} /chosen bootargs "$bootargs ${KERNEL_ARGS}"
    fi
    ln -sf "${DEPLOY_DIR_IMAGE}/cboot-${MACHINE}.bin" ./cboot.bin
    ln -sf "${DEPLOY_DIR_IMAGE}/tos-${MACHINE}.img" ./tos-trusty.img
    for f in ${BOOTFILES}; do
        ln -s "${STAGING_DATADIR}/tegraflash/$f" .
    done
    BOARDREV="c03"
    BPFDTBREV="c04"
    PMICREV="c04"
    cp ${STAGING_DATADIR}/tegraflash/flashvars .
    . ./flashvars
    for var in $FLASHVARS; do
        eval pat=$`echo $var`
        if [ -z "$pat" ]; then
            echo "ERR: missing variable: $var" >&2
            exit 1
        fi
        fnglob=`echo $pat | sed -e"s,@BPFDTBREV@,\*," -e"s,@BOARDREV@,\*," -e"s,@PMICREV@,\*," -e"s,@CHIPREV@,\*,"`
        for fname in ${STAGING_DATADIR}/tegraflash/$fnglob; do
            if [ ! -e $fname ]; then
               bbfatal "$var file(s) not found"
            fi
            ln -sf $fname ./
        done
    done
    ln -s ${STAGING_BINDIR_NATIVE}/tegra186-flash .
    dd if=/dev/zero of=badpage.bin bs=4096 count=1

    #cp -r ${STAGING_BINDIR_NATIVE}/tegra186-flash/* .

    cp ${WORKDIR}/resinOS-flash186.xml ./flash.xml.in

    flashapp=$(which tegraflash.py)
    python3 ${flashapp} \
    --bl nvtboot_recovery_cpu.bin \
    --sdram_config ${MACHINE}.cfg \
    --odmdata ${ODMDATA} \
    --applet mb1_recovery_prod.bin \
    --cmd "sign"  \
    --cfg flash.xml.in \
    --chip 0x18 \
    --misc_config tegra186-mb1-bct-misc-si-l4t.cfg \
    --pinmux_config tegra186-mb1-bct-pinmux-quill-p3310-1000-c03.cfg \
    --pmic_config tegra186-mb1-bct-pmic-quill-p3310-1000-c04.cfg \
    --pmc_config tegra186-mb1-bct-pad-quill-p3310-1000-c03.cfg \
    --prod_config tegra186-mb1-bct-prod-quill-p3310-1000-c03.cfg \
    --scr_config minimal_scr.cfg \
    --scr_cold_boot_config mobile_scr.cfg \
    --br_cmd_config tegra186-mb1-bct-bootrom-quill-p3310-1000-c03.cfg \
    --dev_params emmc.cfg \
    --keep --skipuid  & \
        export _PID=$! ; wait ${_PID} || true

    rm ${_PID}/badpage.bin
    cp -r ${_PID}/* ${DEPLOY_DIR_IMAGE}/bootfiles/
    rm -rf ${_PID}

    # Disable cboot displayed vendor logo
    dd if=/dev/zero of=./bmp.blob count=1 bs=70900

    cp -r signed/* ${DEPLOY_DIR_IMAGE}/bootfiles/
    cp -r *.img    ${DEPLOY_DIR_IMAGE}/bootfiles/
    cp -r *.bin    ${DEPLOY_DIR_IMAGE}/bootfiles/
    cp -r *.blob   ${DEPLOY_DIR_IMAGE}/bootfiles/
    cp ${DEPLOY_DIR_IMAGE}/${DTBFILE} ${DEPLOY_DIR_IMAGE}/bootfiles/

    # This is the new boot0, which needs to be patched due to some bytes
    # that differ between our generated image and tegra host tools one, most
    # probably they are added during flashing
    dd if=${DEPLOY_DIR_IMAGE}/bootfiles/br_bct_BR.bct of=boot0.img conv=notrunc
    dd if=${DEPLOY_DIR_IMAGE}/bootfiles/br_bct_BR.bct of=boot0.img seek=3584 bs=1 conv=notrunc
    dd if=${DEPLOY_DIR_IMAGE}/bootfiles/br_bct_BR.bct of=boot0.img seek=16384 bs=1 conv=notrunc
    dd if=${DEPLOY_DIR_IMAGE}/bootfiles/mb1_prod.bin.encrypt of=boot0.img seek=32768 bs=1 conv=notrunc
    dd if=${DEPLOY_DIR_IMAGE}/bootfiles/mb1_prod.bin.encrypt of=boot0.img seek=294912 bs=1 conv=notrunc
    dd if=${DEPLOY_DIR_IMAGE}/bootfiles/mb1_cold_boot_bct_MB1.bct of=boot0.img seek=557456 bs=1 conv=notrunc
    dd if=${DEPLOY_DIR_IMAGE}/bootfiles/mb1_cold_boot_bct_MB1.bct of=boot0.img seek=622992 bs=1 conv=notrunc
    dd if=${DEPLOY_DIR_IMAGE}/bootfiles/dram-ecc_sigheader.bin.encrypt of=boot0.img seek=688128 bs=1 conv=notrunc
    dd if=${DEPLOY_DIR_IMAGE}/bootfiles/badpage_sigheader.bin.encrypt of=boot0.img seek=743424 bs=1 conv=notrunc
    dd if=${DEPLOY_DIR_IMAGE}/bootfiles/badpage_sigheader.bin.encrypt of=boot0.img seek=748032 bs=1 conv=notrunc
    dd if=${DEPLOY_DIR_IMAGE}/bootfiles/spe_sigheader.bin.encrypt of=boot0.img seek=752640 bs=1 conv=notrunc
    dd if=${DEPLOY_DIR_IMAGE}/bootfiles/spe_sigheader.bin.encrypt of=boot0.img seek=883712 bs=1 conv=notrunc
    dd if=${DEPLOY_DIR_IMAGE}/bootfiles/nvtboot_sigheader.bin.encrypt of=boot0.img seek=1014784 bs=1 conv=notrunc
    dd if=${DEPLOY_DIR_IMAGE}/bootfiles/nvtboot_sigheader.bin.encrypt of=boot0.img seek=1276928 bs=1 conv=notrunc
    dd if=${DEPLOY_DIR_IMAGE}/bootfiles/preboot_d15_prod_cr_sigheader.bin.encrypt of=boot0.img seek=1539072 bs=1 conv=notrunc
    dd if=${DEPLOY_DIR_IMAGE}/bootfiles/preboot_d15_prod_cr_sigheader.bin.encrypt of=boot0.img seek=1801216 bs=1 conv=notrunc
    dd if=${DEPLOY_DIR_IMAGE}/bootfiles/slot_metadata.bin of=boot0.img seek=2063360 bs=1 conv=notrunc
    dd if=${DEPLOY_DIR_IMAGE}/bootfiles/slot_metadata.bin of=boot0.img seek=2067456 bs=1 conv=notrunc
    dd if=/dev/zero of=boot0.img seek=2071552 bs=1 count=2122752 conv=notrunc

    # Patch resulting boot0.img to match partition table for L4T 32.4.2
    dd if=${WORKDIR}/boot0_t186.bindiff bs=1 seek=557056 count=24 skip=0  of=boot0.img conv=notrunc
    dd if=${WORKDIR}/boot0_t186.bindiff bs=1 seek=557444 count=6  skip=24 of=boot0.img conv=notrunc
    dd if=${WORKDIR}/boot0_t186.bindiff bs=1 seek=622592 count=24 skip=30 of=boot0.img conv=notrunc
    dd if=${WORKDIR}/boot0_t186.bindiff bs=1 seek=622980 count=6  skip=54 of=boot0.img conv=notrunc
    cp boot0.img ${DEPLOY_DIR_IMAGE}/bootfiles/boot0.img
}

do_configure_jetson-tx2-4gb() {
    local f

    sed -i -e "s/\[DTB_NAME\]/$(echo ${DTBFILE} | cut -d '.' -f 1)/g" ${WORKDIR}/partition_specification186-4gb.txt
    sed -i -e "s/DTB_NAME/${DTBFILE}/g" ${WORKDIR}/resinOS-flash186-4gb.xml
    if [ -d ${DEPLOY_DIR_IMAGE}/bootfiles ]; then
         rm -rf ${DEPLOY_DIR_IMAGE}/bootfiles
    fi;

    if [ -d ${DEPLOY_DIR_IMAGE}/tegra-binaries ]; then
         rm -rf ${DEPLOY_DIR_IMAGE}/tegra-binaries
    fi;

    mkdir -p ${DEPLOY_DIR_IMAGE}/bootfiles
    local f
    PATH="${STAGING_BINDIR_NATIVE}/tegra186-flash:${PATH}"
    rm -rf "${WORKDIR}/tegraflash"
    mkdir -p "${WORKDIR}/tegraflash"
    oldwd=`pwd`
    cd "${WORKDIR}/tegraflash"
    ln -s "${STAGING_DATADIR}/tegraflash/${MACHINE}.cfg" .
    ln -s "${IMAGE_TEGRAFLASH_KERNEL}" ./${LNXFILE}
    cp "${DEPLOY_DIR_IMAGE}/${DTBFILE}" ./${DTBFILE}

    if [ "${BL_IS_CBOOT}" = "1" -a -n "${KERNEL_ARGS}" ]; then
        cp "${DEPLOY_DIR_IMAGE}/${DTBFILE}" ./${DTBFILE}
        bootargs="`fdtget ./${DTBFILE} /chosen bootargs 2>/dev/null`"
        fdtput -t s ./${DTBFILE} /chosen bootargs "$bootargs ${KERNEL_ARGS}"
    fi
    ln -sf "${DEPLOY_DIR_IMAGE}/cboot-${MACHINE}.bin" ./cboot.bin
    ln -sf "${DEPLOY_DIR_IMAGE}/tos-${MACHINE}.img" ./tos-trusty.img
    for f in ${BOOTFILES}; do
        ln -s "${STAGING_DATADIR}/tegraflash/$f" .
    done
    BOARDREV="c03"
    BPFDTBREV="c04"
    PMICREV="c04"
    cp ${STAGING_DATADIR}/tegraflash/flashvars .
    . ./flashvars
    for var in $FLASHVARS; do
        eval pat=$`echo $var`
        if [ -z "$pat" ]; then
            echo "ERR: missing variable: $var" >&2
            exit 1
        fi
        fnglob=`echo $pat | sed -e"s,@BPFDTBREV@,\*," -e"s,@BOARDREV@,\*," -e"s,@PMICREV@,\*," -e"s,@CHIPREV@,\*,"`
        for fname in ${STAGING_DATADIR}/tegraflash/$fnglob; do
            if [ ! -e $fname ]; then
               bbfatal "$var file(s) not found"
            fi
            ln -sf $fname ./
        done
    done
    ln -s ${STAGING_BINDIR_NATIVE}/tegra186-flash .
    dd if=/dev/zero of=badpage.bin bs=4096 count=1

    #cp -r ${STAGING_BINDIR_NATIVE}/tegra186-flash/* .

    cp ${WORKDIR}/resinOS-flash186-4gb.xml ./flash.xml.in

    flashapp=$(which tegraflash.py)
    python3 ${flashapp} \
    --bl nvtboot_recovery_cpu.bin \
    --sdram_config ${MACHINE}.cfg \
    --odmdata ${ODMDATA} \
    --applet mb1_recovery_prod.bin \
    --cmd "sign"  \
    --cfg flash.xml.in \
    --chip 0x18 \
    --misc_config tegra186-mb1-bct-misc-si-l4t.cfg \
    --pinmux_config tegra186-mb1-bct-pinmux-quill-p3489-1000-a00.cfg \
    --pmic_config tegra186-mb1-bct-pmic-lightning-p3489-1000-a00.cfg \
    --pmc_config tegra186-mb1-bct-pad-quill-p3489-1000-a00.cfg \
    --prod_config tegra186-mb1-bct-prod-storm-p3489-1000-a00.cfg \
    --scr_config minimal_scr.cfg \
    --scr_cold_boot_config mobile_scr.cfg \
    --br_cmd_config tegra186-mb1-bct-bootrom-quill-p3489-1000-a00.cfg \
    --dev_params emmc.cfg \
    --keep --skipuid  & \
        export _PID=$! ; wait ${_PID} || true

    rm ${_PID}/badpage.bin
    cp -r ${_PID}/* ${DEPLOY_DIR_IMAGE}/bootfiles/
    rm -rf ${_PID}

    # Disable cboot displayed vendor logo
    dd if=/dev/zero of=./bmp.blob count=1 bs=70900

    cp -r signed/* ${DEPLOY_DIR_IMAGE}/bootfiles/
    cp -r *.img    ${DEPLOY_DIR_IMAGE}/bootfiles/
    cp -r *.bin    ${DEPLOY_DIR_IMAGE}/bootfiles/
    cp -r *.blob   ${DEPLOY_DIR_IMAGE}/bootfiles/
    cp ${DEPLOY_DIR_IMAGE}/${DTBFILE} ${DEPLOY_DIR_IMAGE}/bootfiles/

    # This is the new boot0, which needs to be patched due to some bytes
    # that differ between our generated image and tegra host tools one, most
    # probably they are added during flashing
    dd if=${DEPLOY_DIR_IMAGE}/bootfiles/br_bct_BR.bct of=boot0.img conv=notrunc
    dd if=${DEPLOY_DIR_IMAGE}/bootfiles/br_bct_BR.bct of=boot0.img seek=3584 bs=1 conv=notrunc
    dd if=${DEPLOY_DIR_IMAGE}/bootfiles/br_bct_BR.bct of=boot0.img seek=16384 bs=1 conv=notrunc
    dd if=${DEPLOY_DIR_IMAGE}/bootfiles/mb1_prod.bin.encrypt of=boot0.img seek=32768 bs=1 conv=notrunc
    dd if=${DEPLOY_DIR_IMAGE}/bootfiles/mb1_prod.bin.encrypt of=boot0.img seek=294912 bs=1 conv=notrunc
    dd if=${DEPLOY_DIR_IMAGE}/bootfiles/mb1_cold_boot_bct_MB1.bct of=boot0.img seek=557456 bs=1 conv=notrunc
    dd if=${DEPLOY_DIR_IMAGE}/bootfiles/mb1_cold_boot_bct_MB1.bct of=boot0.img seek=622992 bs=1 conv=notrunc
    dd if=${DEPLOY_DIR_IMAGE}/bootfiles/dram-ecc_sigheader.bin.encrypt of=boot0.img seek=688128 bs=1 conv=notrunc
    dd if=${DEPLOY_DIR_IMAGE}/bootfiles/badpage_sigheader.bin.encrypt of=boot0.img seek=743424 bs=1 conv=notrunc
    dd if=${DEPLOY_DIR_IMAGE}/bootfiles/badpage_sigheader.bin.encrypt of=boot0.img seek=748032 bs=1 conv=notrunc
    dd if=${DEPLOY_DIR_IMAGE}/bootfiles/spe_sigheader.bin.encrypt of=boot0.img seek=752640 bs=1 conv=notrunc
    dd if=${DEPLOY_DIR_IMAGE}/bootfiles/spe_sigheader.bin.encrypt of=boot0.img seek=883712 bs=1 conv=notrunc
    dd if=${DEPLOY_DIR_IMAGE}/bootfiles/nvtboot_sigheader.bin.encrypt of=boot0.img seek=1014784 bs=1 conv=notrunc
    dd if=${DEPLOY_DIR_IMAGE}/bootfiles/nvtboot_sigheader.bin.encrypt of=boot0.img seek=1276928 bs=1 conv=notrunc
    dd if=${DEPLOY_DIR_IMAGE}/bootfiles/preboot_d15_prod_cr_sigheader.bin.encrypt of=boot0.img seek=1539072 bs=1 conv=notrunc
    dd if=${DEPLOY_DIR_IMAGE}/bootfiles/preboot_d15_prod_cr_sigheader.bin.encrypt of=boot0.img seek=1801216 bs=1 conv=notrunc
    dd if=${DEPLOY_DIR_IMAGE}/bootfiles/slot_metadata.bin of=boot0.img seek=2063360 bs=1 conv=notrunc
    dd if=${DEPLOY_DIR_IMAGE}/bootfiles/slot_metadata.bin of=boot0.img seek=2067456 bs=1 conv=notrunc
    dd if=/dev/zero of=boot0.img seek=2071552 bs=1 count=2122752 conv=notrunc

    # Patch resulting boot0.img to match partition table for L4T 32.4.2
    dd if=${WORKDIR}/boot0_t186-4gb.bindiff bs=1 seek=557056 count=24 skip=0  of=boot0.img conv=notrunc
    dd if=${WORKDIR}/boot0_t186-4gb.bindiff bs=1 seek=557444 count=6  skip=24 of=boot0.img conv=notrunc
    dd if=${WORKDIR}/boot0_t186-4gb.bindiff bs=1 seek=622592 count=24 skip=30 of=boot0.img conv=notrunc
    dd if=${WORKDIR}/boot0_t186-4gb.bindiff bs=1 seek=622980 count=6  skip=54 of=boot0.img conv=notrunc
    cp boot0.img ${DEPLOY_DIR_IMAGE}/bootfiles/boot0.img
}

do_install_jetson-tx2() {
    install -d ${D}/${BINARY_INSTALL_PATH}
    cp -r ${S}/tegraflash/signed/* ${D}/${BINARY_INSTALL_PATH}
    cp ${WORKDIR}/partition_specification186.txt ${D}/${BINARY_INSTALL_PATH}/
    cp ${DEPLOY_DIR_IMAGE}/bootfiles/boot0.img ${D}/${BINARY_INSTALL_PATH}/
    rm -rf ${DEPLOY_DIR_IMAGE}/tegra-binaries
}

do_install_jetson-tx2-4gb() {
    install -d ${D}/${BINARY_INSTALL_PATH}
    cp -r ${S}/tegraflash/signed/* ${D}/${BINARY_INSTALL_PATH}
    cp ${WORKDIR}/partition_specification186-4gb.txt ${D}/${BINARY_INSTALL_PATH}/partition_specification186.txt
    cp ${DEPLOY_DIR_IMAGE}/bootfiles/boot0.img ${D}/${BINARY_INSTALL_PATH}/
    rm -rf ${DEPLOY_DIR_IMAGE}/tegra-binaries
}

do_deploy() {
    rm -rf ${DEPLOYDIR}/$(basename ${BINARY_INSTALL_PATH})
    mkdir -p ${DEPLOYDIR}/$(basename ${BINARY_INSTALL_PATH})
    cp -r ${D}/${BINARY_INSTALL_PATH}/* ${DEPLOYDIR}/$(basename ${BINARY_INSTALL_PATH})
    rm -rf ${DEPLOY_DIR_IMAGE}/$(basename ${BINARY_INSTALL_PATH})
}

FILES_${PN} += "${BINARY_INSTALL_PATH}"

INHIBIT_PACKAGE_STRIP = "1"
INHIBIT_PACKAGE_DEBUG_SPLIT = "1"

do_install[nostamp] = "1"
do_compile[nostamp] = "1"
do_deploy[nostamp] = "1"
do_configure[nostamp] = "1"

do_configure[depends] += "tegra-binaries:do_preconfigure"
do_compile[depends] += " \
    virtual/kernel:do_deploy \
    virtual/bootloader:do_deploy \
"

do_install[depends] += "virtual/kernel:do_deploy"
do_configure[depends] += " cboot:do_deploy"
do_configure[depends] += " tos-prebuilt:do_deploy"
do_populate_lic[depends] += "tegra-binaries:do_unpack"

addtask do_deploy before do_package after do_install
