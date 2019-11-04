SUMMARY = "Create signed and encrypted binaries for Jetson Nano"

LICENSE = "Apache-2.0"
LIC_FILES_CHKSUM = "file://${RESIN_COREBASE}/COPYING.Apache-2.0;md5=89aea4e17d99a7cacdbeed46a0096b10"

IMAGE_ROOTFS_ALIGNMENT ?= "4"

DEPENDS = " \
    coreutils-native \
    virtual/bootloader \
    virtual/kernel \
    tegra-binaries \
    tegra-bootfiles \
    tegra210-flashtools-native \
    dtc-native \
    "

inherit deploy pythonnative perlnative

SRC_URI = " \
    file://resinOS-flash210.xml \
    file://partition_specification210.txt \
    "

KERNEL_DEVICETREE_jetson-nano = "${DEPLOY_DIR_IMAGE}/tegra210-p3448-0000-p3449-0000-a02.dtb"
DTBFILE ?= "${@os.path.basename(d.getVar('KERNEL_DEVICETREE', True).split()[0])}"
LNXSIZE ?= "67108864"

IMAGE_TEGRAFLASH_FS_TYPE ??= "ext4"
IMAGE_TEGRAFLASH_ROOTFS ?= "${IMGDEPLOYDIR}/${IMAGE_LINK_NAME}.${IMAGE_TEGRAFLASH_FS_TYPE}"

BL_IS_CBOOT = "${@'1' if d.getVar('PREFERRED_PROVIDER_virtual/bootloader').startswith('cboot') else '0'}"

LDK_DIR = "${TMPDIR}/work-shared/L4T-${SOC_FAMILY}-${PV}-${PR}/Linux_for_Tegra"
B = "${WORKDIR}/build"
S = "${WORKDIR}"
LNXFILE="${KERNEL_IMAGETYPE}${KERNEL_INITRAMFS}-${MACHINE}.bin"
IMAGE_TEGRAFLASH_KERNEL ?= "${DEPLOY_DIR_IMAGE}/${LNXFILE}"
BINARY_INSTALL_PATH = "/opt/tegra-binaries"

tegraflash_roundup_size() {
    local actsize=$(stat -L -c "%s" "$1")
    local blks=$(expr \( $actsize + 4095 \) / 4096)
    expr $blks \* 4096
}

BOOTFILES=" \
    bmp.blob \
    cboot.bin \
    eks.img \
    nvtboot_recovery.bin \
    nvtboot.bin \
    nvtboot_cpu.bin \
    warmboot.bin \
    rp4.blob \
    sc7entry-firmware.bin \
    tos-mon-only.img \
"

do_configure() {
    local destdir="${WORKDIR}/tegraflash"
    local lnxfile="${LNXFILE}"
    local f
    PATH="${STAGING_BINDIR_NATIVE}/tegra210-flash:${PATH}"
    rm -rf "${WORKDIR}/tegraflash"
    mkdir -p "${WORKDIR}/tegraflash"
    oldwd=`pwd`
    cd "${WORKDIR}/tegraflash"
    ln -s "${STAGING_DATADIR}/tegraflash/${MACHINE}.cfg" .
    ln -s "${IMAGE_TEGRAFLASH_KERNEL}" ./${LNXFILE}
    cp "${DEPLOY_DIR_IMAGE}/${DTBFILE}" ./${DTBFILE}
    cp "${DEPLOY_DIR_IMAGE}/u-boot-dtb.bin" ./u-boot-dtb.bin

    if [ -n "${KERNEL_ARGS}" ]; then
        # Disable framebuffer console
        KERNEL_ARGS_NO_FBCONSOLE=$(echo ${KERNEL_ARGS} | sed 's/console=tty0/ /g')
        fdtput -t s ./${DTBFILE} /chosen bootargs "${KERNEL_ARGS_NO_FBCONSOLE}"
    else
        fdtput -d ./${DTBFILE} /chosen bootargs
    fi

    for f in ${BOOTFILES}; do
        cp "${STAGING_DATADIR}/tegraflash/$f" .
    done

    ln -s ${STAGING_BINDIR_NATIVE}/tegra210-flash .
    mkdir -p ${DEPLOY_DIR_IMAGE}/bootfiles

    # tegraflash.py script will sign all binaries
    # mentioned for signing in flash.xml.in
    cp ${WORKDIR}/resinOS-flash210.xml flash.210.in

    # prep env for tegraflash
    ln -sf ${STAGING_BINDIR_NATIVE}/tegra210-flash/${SOC_FAMILY}-flash-helper.sh ./
    ln -sf ${STAGING_BINDIR_NATIVE}/tegra210-flash/tegraflash.py ./

    rm -rf signed

    local destdir="$1"
    local gptsize="$2"
    local ebtsize=$(tegraflash_roundup_size cboot.bin)
    local nvcsize=$(tegraflash_roundup_size nvtboot.bin)
    local tbcsize=$(tegraflash_roundup_size nvtboot_cpu.bin)
    local dtbsize=$(tegraflash_roundup_size ${DTBFILE})
    local bpfsize=$(tegraflash_roundup_size sc7entry-firmware.bin)
    local wb0size=$(tegraflash_roundup_size warmboot.bin)
    local tossize=$(tegraflash_roundup_size tos-mon-only.img)

    head -n 1 ${STAGING_DATADIR}/nv_tegra/nv_tegra_release > ./nv_tegra_release

    cat "flash.210.in" | sed \
        -e"s,EBTFILE,cboot.bin," -e"s,EBTSIZE,$ebtsize," \
        -e"s,LNXFILE,u-boot-dtb.bin," \
        -e"/NCTFILE/d" -e"s,NCTTYPE,data," \
        -e"/SOSFILE/d" \
        -e"s,NXC,NVC," -e"s,NVCTYPE,bootloader," -e"s,NVCFILE,nvtboot.bin," -e "s,NVCSIZE,$nvcsize," \
        -e"s,MPBTYPE,data," -e"/MPBFILE/d" \
        -e"s,MBPTYPE,data," -e"/MBPFILE/d" \
        -e"s,BXF,BPF," -e"s,BPFFILE,sc7entry-firmware.bin," -e"s,BPFSIZE,$bpfsize," \
        -e"/BPFDTB-FILE/d" \
        -e"s,WX0,WB0," -e"s,WB0TYPE,WB0," -e"s,WB0FILE,warmboot.bin," -e"s,WB0SIZE,$wb0size," \
        -e"s,TXS,TOS," -e"s,TOSFILE,tos-mon-only.img," -e"s,TOSSIZE,$tossize," \
        -e"s,EXS,EKS," -e"s,EKSFILE,eks.img," \
        -e"s,FBTYPE,data," -e"/FBFILE/d" \
        -e"s,DXB,DTB," -e"s,DTBFILE,${DTBFILE}," -e"s,DTBSIZE,$dtbsize," \
        -e"s,TXC,TBC," -e"s,TBCTYPE,bootloader," -e"s,TBCFILE,nvtboot_cpu.bin," -e"s,TBCSIZE,$tbcsize,"  \
        -e"s,EFISIZE,67108864," -e"/EFIFILE/d" \
        -e"s,BCTSIZE,${BOOTPART_SIZE}," -e"s,PPTSIZE,$gptsize," \
        -e"s,PPTFILE,ppt.img," -e"s,GPTFILE,gpt.img," \
        > ./flash.xml.in

    python tegraflash.py --bl cboot.bin --bldtb "${DTBFILE}" --chip 0x21 --applet nvtboot_recovery.bin --bct "${MACHINE}.cfg" --cfg flash.xml.in --cmd "sign"

    # Disable cboot displayed vendor logo
    dd if=/dev/zero of=./bmp.blob count=1 bs=70900

    cp -r signed/* ${DEPLOY_DIR_IMAGE}/bootfiles/
    cp -r *.img    ${DEPLOY_DIR_IMAGE}/bootfiles/
    cp -r *.bin    ${DEPLOY_DIR_IMAGE}/bootfiles/
    cp -r *.blob   ${DEPLOY_DIR_IMAGE}/bootfiles/
    cp ${DEPLOY_DIR_IMAGE}/${DTBFILE} ${DEPLOY_DIR_IMAGE}/bootfiles/
}

do_install() {
    install -d ${D}/${BINARY_INSTALL_PATH}
    cp -r ${S}/tegraflash/signed/* ${D}/${BINARY_INSTALL_PATH}
    cp ${WORKDIR}/partition_specification210.txt ${D}/${BINARY_INSTALL_PATH}/
}

do_deploy() {
    rm -rf ${DEPLOYDIR}/$(basename ${BINARY_INSTALL_PATH})
    mkdir -p ${DEPLOYDIR}/$(basename ${BINARY_INSTALL_PATH})
    cp -r ${D}/${BINARY_INSTALL_PATH}/* ${DEPLOYDIR}/$(basename ${BINARY_INSTALL_PATH})
}

FILES_${PN} += "${BINARY_INSTALL_PATH}"

INHIBIT_PACKAGE_STRIP = "1"
INHIBIT_PACKAGE_DEBUG_SPLIT = "1"

do_install[nostamp] = "1"
do_deploy[nostamp] = "1"
do_configure[nostamp] = "1"

do_configure[depends] += " virtual/bootloader:do_deploy"
do_configure[depends] += " tegra-binaries:do_preconfigure"
do_configure[depends] += " virtual/kernel:do_deploy"
do_populate_lic[depends] += " tegra-binaries:do_unpack"

addtask do_deploy before do_package after do_install

COMPATIBLE_MACHINE = "jetson-nano"